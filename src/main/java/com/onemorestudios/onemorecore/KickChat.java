package com.onemorestudios.onemorecore;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Reads one Kick chat, read only, through the Pusher WebSocket that the kick.com web page uses. Kick documents no such
 * chat API: the key request, the channel name "chatrooms.<id>.v2" and the event name come from the public web client
 * and may change. The channel is a Kick channel name (the chatroom id is read from kick.com/api/v2/channels/<name>) or the
 * chatroom id itself, digits only, for when that page refuses the server.
 */
public class KickChat implements WebSocket.Listener {
    /** The web client asks this for its realtime key; the answer names the Pusher app key and cluster. */
    private static final String REALTIME = "https://web.kick.com/api/v1/realtime/connection";
    private static final Pattern APP_KEY = Pattern.compile("\"app_key\"\s*:\s*\"([a-z0-9]+)\""), CLUSTER = Pattern.compile("\"cluster\"\s*:\s*\"([a-z0-9-]+)\"");
    private static final Pattern CHATROOM = Pattern.compile("\"chatroom\"\\s*:\\s*\\{[^{}]*?\"id\"\\s*:\\s*(\\d+)");
    private static final int MAX_BACKOFF = 60;
    /** A full browser name: the pages answer an unknown or short one with "update your browser". */
    static final String UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String channel;
    private final Consumer<TwitchChat.Message> onMessage;
    private final StringBuilder buffer = new StringBuilder();
    private volatile boolean stopped;
    private volatile WebSocket socket;
    private String chatroom;
    /** Pusher app key and cluster (2026-11: 34bf7a0ff419a2a775b9, us2), asked from kick.com at the start. */
    private String appKey, cluster;
    private int backoff = 1;

    public KickChat(String channel, Consumer<TwitchChat.Message> onMessage) {
        if (!channel.matches("[a-z0-9_-]{1,32}")) throw new IllegalArgumentException("bad Kick channel name: " + channel);
        this.channel = channel;
        this.onMessage = onMessage;
        if (channel.matches("\\d+")) chatroom = channel;
    }

    public void start() {
        if (stopped) return;
        if (appKey == null) {
            String body = "{\"client\":{\"id\":\"omc-" + java.util.UUID.randomUUID() + "\",\"type\":\"web\"},\"capabilities\":{\"accepted_providers\":[{\"provider\":\"pusher\"}]}}";
            HttpRequest request = HttpRequest.newBuilder(URI.create(REALTIME)).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                    .header("Accept", "application/json").header("User-Agent", UA).POST(HttpRequest.BodyPublishers.ofString(body)).build();
            client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
                Matcher k = error == null && response.statusCode() == 200 ? APP_KEY.matcher(response.body()) : null;
                Matcher c = k != null ? CLUSTER.matcher(response.body()) : null;
                if (k != null && k.find() && c.find()) {
                    appKey = k.group(1);
                    cluster = c.group(1);
                    start();
                } else retry("no realtime key from kick.com (" + (error != null ? error : "HTTP " + response.statusCode()) + ")");
            });
            return;
        }
        if (chatroom == null) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://kick.com/api/v2/channels/" + channel)).timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json").header("User-Agent", UA).build();
            client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
                Matcher m = error == null && response.statusCode() == 200 ? CHATROOM.matcher(response.body()) : null;
                if (m != null && m.find()) {
                    chatroom = m.group(1);
                    start();
                } else retry("no chatroom id from kick.com (" + (error != null ? error : "HTTP " + response.statusCode())
                        + "); put the chatroom id (digits) in chatChannel");
            });
            return;
        }
        client.newWebSocketBuilder().buildAsync(URI.create("wss://ws-" + cluster + ".pusher.com/app/" + appKey + "?protocol=7&client=js&version=8.4.0&flash=false"), this).whenComplete((ws, error) -> {
            if (error != null) retry("connect failed: " + error);
        });
    }

    public void stop() {
        stopped = true;
        WebSocket ws = socket;
        if (ws != null) ws.abort();
        client.shutdownNow();
    }

    private void retry(String reason) {
        if (stopped) return;
        OneMoreCore.LOGGER.warn("Kick chat {}: {}. Next try in {} s.", channel, reason, backoff);
        CompletableFuture.delayedExecutor(backoff, TimeUnit.SECONDS).execute(this::start);
        backoff = Math.min(MAX_BACKOFF, backoff * 2);
    }

    @Override
    public void onOpen(WebSocket ws) {
        socket = ws;
        buffer.setLength(0);
        ws.sendText("{\"event\":\"pusher:subscribe\",\"data\":{\"auth\":\"\",\"channel\":\"chatrooms." + chatroom + ".v2\"}}", true);
        ws.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        buffer.append(data);
        if (last) {
            handle(ws, buffer.toString());
            buffer.setLength(0);
        }
        ws.request(1);
        return null;
    }

    private void handle(WebSocket ws, String text) {
        try {
            JsonObject frame = JsonParser.parseString(text).getAsJsonObject();
            String event = frame.has("event") ? frame.get("event").getAsString() : "";
            if (event.equals("pusher:ping")) ws.sendText("{\"event\":\"pusher:pong\",\"data\":{}}", true);
            else if (event.equals("pusher:error")) {
                // A key that Kick rotated: ask for the key again on the next try.
                appKey = null;
                ws.abort();
                retry("Pusher error " + frame.get("data"));
            }
            else if (event.equals("pusher_internal:subscription_succeeded")) {
                backoff = 1;
                OneMoreCore.LOGGER.info("Kick chat {}: connected.", channel);
            } else {
                TwitchChat.Message m = parse(frame);
                if (m != null) onMessage.accept(m);
            }
        } catch (RuntimeException e) {
            // Not JSON or another shape: ignored.
        }
    }

    /** A chat message event: its data is a JSON text (Pusher encodes it twice). Null for anything else. */
    static TwitchChat.Message parse(JsonObject frame) {
        if (!frame.has("event") || !frame.get("event").getAsString().equals("App\\Events\\ChatMessageEvent") || !frame.has("data")) return null;
        JsonObject data = JsonParser.parseString(frame.get("data").getAsString()).getAsJsonObject();
        JsonObject sender = data.getAsJsonObject("sender");
        if (sender == null || !data.has("content")) return null;
        String user = sender.has("slug") ? sender.get("slug").getAsString() : sender.get("username").getAsString();
        return new TwitchChat.Message(user.toLowerCase(java.util.Locale.ROOT), sender.get("username").getAsString(), data.get("content").getAsString());
    }

    @Override
    public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
        retry("closed (" + status + ")");
        return null;
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
        retry("error: " + error);
    }
}
