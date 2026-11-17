package com.onemorestudios.onemoreclient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Reads one Twitch chat over IRC on WebSocket (https://dev.twitch.tv/docs/chat/irc/). Read only: it never sends a chat message.
 * The login is anonymous (nick "justinfan" + digits, no token). Twitch accepts this login but does not document it.
 * Messages arrive on a network thread; the consumer must hand them to the server thread.
 */
public class TwitchChat implements WebSocket.Listener {
    /** One chat message. user is the login name, displayName is the nickname that the chat shows. */
    public record Message(String user, String displayName, String text) {}

    private static final URI URL = URI.create("wss://irc-ws.chat.twitch.tv:443");
    private static final int MAX_BACKOFF = 60;

    private final HttpClient client = HttpClient.newHttpClient();
    private final String channel;
    private final Consumer<Message> onMessage;
    private final StringBuilder buffer = new StringBuilder();
    private volatile boolean stopped;
    private volatile WebSocket socket;
    private int backoff = 1;

    /** channel: a Twitch login name, 1 to 25 characters of [a-z0-9_]. */
    public TwitchChat(String channel, Consumer<Message> onMessage) {
        if (!channel.matches("[a-z0-9_]{1,25}")) throw new IllegalArgumentException("bad Twitch channel name: " + channel);
        this.channel = channel;
        this.onMessage = onMessage;
    }

    public void start() {
        if (stopped) return;
        client.newWebSocketBuilder().buildAsync(URL, this).whenComplete((ws, error) -> {
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
        OneMoreClient.LOGGER.warn("Twitch chat #{}: {}. Next try in {} s.", channel, reason, backoff);
        CompletableFuture.delayedExecutor(backoff, TimeUnit.SECONDS).execute(this::start);
        backoff = Math.min(MAX_BACKOFF, backoff * 2);
    }

    @Override
    public void onOpen(WebSocket ws) {
        socket = ws;
        buffer.setLength(0);
        // The tags capability gives the display-name tag. Twitch requires the commands capability with it.
        ws.sendText("CAP REQ :twitch.tv/tags twitch.tv/commands", true)
                .thenCompose(w -> w.sendText("NICK justinfan" + ThreadLocalRandom.current().nextInt(10000, 100000), true))
                .thenCompose(w -> w.sendText("JOIN #" + channel, true));
        ws.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        buffer.append(data);
        if (last) {
            // One frame can hold several IRC messages.
            for (String line : buffer.toString().split("\r\n")) handle(ws, line);
            buffer.setLength(0);
        }
        ws.request(1);
        return null;
    }

    private void handle(WebSocket ws, String line) {
        if (line.startsWith("PING")) {
            // The PONG text must be the PING text.
            ws.sendText("PONG" + line.substring(4), true);
        } else if (line.startsWith(":tmi.twitch.tv RECONNECT")) {
            ws.abort();
            retry("server asked for a reconnect");
        } else {
            Message m = parse(line);
            if (m != null) {
                onMessage.accept(m);
            } else if (line.contains(" 366 ")) { // End of the names list: the JOIN worked.
                backoff = 1;
                OneMoreClient.LOGGER.info("Twitch chat #{}: connected.", channel);
            }
        }
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

    /** Parses "[@tags ]:nick!user@host PRIVMSG #channel :text". Null for every other line. */
    static Message parse(String line) {
        String tags = "";
        if (line.startsWith("@")) {
            int space = line.indexOf(' ');
            if (space < 0) return null;
            tags = line.substring(1, space);
            line = line.substring(space + 1).stripLeading();
        }
        int bang = line.indexOf('!'), cmd = line.indexOf(" PRIVMSG #"), text = line.indexOf(" :", Math.max(cmd, 0));
        if (!line.startsWith(":") || cmd < 0 || bang < 2 || bang > cmd || text < 0) return null;
        String user = line.substring(1, bang), display = user;
        for (String tag : tags.split(";"))
            if (tag.startsWith("display-name=") && tag.length() > 13) display = tag.substring(13).replace("\\s", " ");
        return new Message(user, display, line.substring(text + 2));
    }
}
