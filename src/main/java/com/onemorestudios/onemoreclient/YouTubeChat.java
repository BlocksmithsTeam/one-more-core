package com.onemorestudios.onemoreclient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Reads the live chat of a YouTube stream, read only, without an API key: like the web page, it asks YouTube's own
 * live_chat/get_live_chat endpoint again and again with the continuation of the last answer. YouTube documents no such
 * use and may change it. The channel is a video id (11 characters) or a channel handle ("@name", its current live
 * stream).
 */
public class YouTubeChat {
    private static final Pattern VIDEO = Pattern.compile("\"videoId\"\\s*:\\s*\"([A-Za-z0-9_-]{11})\""),
            KEY = Pattern.compile("\"INNERTUBE_API_KEY\"\\s*:\\s*\"([^\"]+)\""),
            VERSION = Pattern.compile("\"INNERTUBE_CLIENT_VERSION\"\\s*:\\s*\"([^\"]+)\""),
            CONTINUATION = Pattern.compile("\"continuation\"\\s*:\\s*\"([^\"]+)\"");
    private static final int MAX_BACKOFF = 60;
    /** A full browser name: the pages answer an unknown or short one with "update your browser". */
    static final String UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private final String channel;
    private final Consumer<TwitchChat.Message> onMessage;
    private volatile boolean stopped;
    private String key, version, continuation;
    private int backoff = 1;

    public YouTubeChat(String channel, Consumer<TwitchChat.Message> onMessage) {
        if (!channel.matches("@?[A-Za-z0-9_.-]{1,64}")) throw new IllegalArgumentException("bad YouTube channel or video: " + channel);
        this.channel = channel;
        this.onMessage = onMessage;
    }

    public void start() {
        CompletableFuture.runAsync(this::run);
    }

    public void stop() {
        stopped = true;
        client.shutdownNow();
    }

    private String get(String url) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("User-Agent", UA)
                .header("Accept-Language", "en").build();
        HttpResponse<String> response = client.send(r, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode() + " for " + url);
        return response.body();
    }

    private static String find(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** Finds the stream and the first continuation, then polls until stopped; restarts with a growing pause on errors. */
    private void run() {
        while (!stopped) {
            try {
                String video = channel.length() == 11 && !channel.startsWith("@") ? channel
                        : find(VIDEO, get("https://www.youtube.com/" + (channel.startsWith("@") ? channel : "@" + channel) + "/live"));
                if (video == null) throw new IllegalStateException("no live stream found");
                String page = get("https://www.youtube.com/live_chat?is_popout=1&v=" + video);
                key = find(KEY, page);
                version = find(VERSION, page);
                continuation = find(CONTINUATION, page);
                if (key == null || version == null || continuation == null) throw new IllegalStateException("the chat page has no continuation");
                OneMoreClient.LOGGER.info("YouTube chat {}: connected to {}.", channel, video);
                backoff = 1;
                poll();
            } catch (Exception e) {
                if (stopped) return;
                OneMoreClient.LOGGER.warn("YouTube chat {}: {}. Next try in {} s.", channel, e.getMessage(), backoff);
                sleep(backoff * 1000L);
                backoff = Math.min(MAX_BACKOFF, backoff * 2);
            }
        }
    }

    private void poll() throws Exception {
        while (!stopped) {
            JsonObject body = new JsonObject();
            JsonObject context = new JsonObject(), web = new JsonObject();
            web.addProperty("clientName", "WEB");
            web.addProperty("clientVersion", version);
            context.add("client", web);
            body.add("context", context);
            body.addProperty("continuation", continuation);
            HttpRequest r = HttpRequest.newBuilder(URI.create("https://www.youtube.com/youtubei/v1/live_chat/get_live_chat?key=" + key))
                    .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json").header("User-Agent", UA)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            HttpResponse<String> response = client.send(r, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
            JsonObject chat = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("continuationContents");
            if (chat == null || !chat.has("liveChatContinuation")) throw new IllegalStateException("the stream ended");
            JsonObject live = chat.getAsJsonObject("liveChatContinuation");
            for (TwitchChat.Message m : messages(live)) onMessage.accept(m);
            long wait = 5000;
            String next = null;
            for (JsonElement c : live.getAsJsonArray("continuations")) {
                for (String kind : new String[] {"invalidationContinuationData", "timedContinuationData", "reloadContinuationData"}) {
                    JsonObject d = c.getAsJsonObject().getAsJsonObject(kind);
                    if (d == null) continue;
                    next = d.get("continuation").getAsString();
                    if (d.has("timeoutMs")) wait = d.get("timeoutMs").getAsLong();
                }
            }
            if (next == null) throw new IllegalStateException("no next continuation");
            continuation = next;
            sleep(Math.max(1000, Math.min(10000, wait)));
        }
    }

    /** The text messages of one answer: author name and the text of all runs (emoji runs give their shortcut). */
    static List<TwitchChat.Message> messages(JsonObject live) {
        List<TwitchChat.Message> out = new ArrayList<>();
        JsonArray actions = live.getAsJsonArray("actions");
        if (actions == null) return out;
        for (JsonElement a : actions) {
            JsonObject add = a.getAsJsonObject().getAsJsonObject("addChatItemAction");
            if (add == null) continue;
            JsonObject text = add.getAsJsonObject("item").getAsJsonObject("liveChatTextMessageRenderer");
            if (text == null || !text.has("authorName") || !text.has("message")) continue;
            String name = text.getAsJsonObject("authorName").get("simpleText").getAsString();
            String id = text.has("authorExternalChannelId") ? text.get("authorExternalChannelId").getAsString() : name;
            StringBuilder sb = new StringBuilder();
            for (JsonElement run : text.getAsJsonObject("message").getAsJsonArray("runs")) {
                JsonObject o = run.getAsJsonObject();
                if (o.has("text")) sb.append(o.get("text").getAsString());
            }
            out.add(new TwitchChat.Message(id, name.startsWith("@") ? name.substring(1) : name, sb.toString()));
        }
        return out;
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); stopped = true; }
    }
}
