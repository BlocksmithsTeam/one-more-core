package com.onemorestudios.onemorecore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The identities that customers can get: a Minecraft account name for the skin and a visible name.
 * Sources: the config list skinNames and the stream chat. Server thread only.
 */
public class SkinPool {
    public record Entry(String displayName, String minecraftName) {}

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final int MAX_NICK = 25;
    private static final int CHAT_PER_TICK = 8;

    /** Chat entries by chat user, oldest first. */
    private static final Map<String, Entry> chat = new LinkedHashMap<>();
    /** The running chat reader: its stop method. */
    private static Runnable reader;
    private static Consumer<ServerTickEvent.Post> chatTick;

    static boolean isMinecraftName(String s) {
        return NAME.matcher(s).matches();
    }

    /** The Minecraft name of a chat message "<command> <name>", or null. Anything else is ignored. */
    static String commandName(String text, String command) {
        String[] words = text.strip().split("\\s+");
        return words.length == 2 && words[0].equalsIgnoreCase(command) && isMinecraftName(words[1]) ? words[1] : null;
    }

    /** Chat nicknames are untrusted: no control, format or section sign characters, limited length. */
    static String cleanNick(String nick) {
        String s = nick.replaceAll("[\\p{C}§]", "").strip();
        return s.codePointCount(0, s.length()) <= MAX_NICK ? s : s.substring(0, s.offsetByCodePoints(0, MAX_NICK));
    }

    /** One entry per chat user: a new command replaces the old one. The oldest entries drop out above the cap. */
    private static void putChat(Map<String, Entry> entries, String user, Entry entry, int cap) {
        entries.remove(user);
        entries.put(user, entry);
        var oldest = entries.keySet().iterator();
        while (entries.size() > cap) { oldest.next(); oldest.remove(); }
    }

    /** Commands from the chat thread waiting for the server tick. Each reader has its own queue. */
    static final class PendingChat {
        private final Map<String, Entry> entries = new LinkedHashMap<>();
        private final int cap;

        PendingChat(int skinPoolSize) {
            cap = skinPoolSize * 2;
        }

        void accept(String platform, String command, TwitchChat.Message message) {
            String name = commandName(message.text(), command);
            if (name == null) return;
            String nick = cleanNick(message.displayName());
            synchronized (this) {
                putChat(entries, platform + ":" + message.user(), new Entry(nick.isEmpty() ? name : nick, name), cap);
            }
        }

        synchronized void drain(BiConsumer<String, Entry> apply) {
            var oldest = entries.entrySet().iterator();
            for (int i = 0; i < CHAT_PER_TICK && oldest.hasNext(); i++) {
                var entry = oldest.next();
                apply.accept(entry.getKey(), entry.getValue());
                oldest.remove();
            }
        }
    }

    /** Starts the chat reader if the config names a channel. The server owner configures it; it runs on the server. */
    static void start(MinecraftServer server) {
        String platform = Config.CHAT_PLATFORM.get(), channel = Config.CHAT_CHANNEL.get().strip();
        if (platform.equals("none") || channel.isEmpty()) return;
        int cap = Config.SKIN_POOL_SIZE.get();
        PendingChat pending = new PendingChat(cap);
        Consumer<TwitchChat.Message> handler = m -> pending.accept(platform, Config.CHAT_COMMAND.get(), m);
        try {
            switch (platform) {
                case "twitch" -> { TwitchChat t = new TwitchChat(channel.toLowerCase(Locale.ROOT), handler); t.start(); reader = t::stop; }
                case "kick" -> { KickChat k = new KickChat(channel.toLowerCase(Locale.ROOT), handler); k.start(); reader = k::stop; }
                case "youtube" -> { YouTubeChat y = new YouTubeChat(channel, handler); y.start(); reader = y::stop; }
                default -> { }
            }
        } catch (IllegalArgumentException e) {
            OneMoreCore.LOGGER.error("Stream chat is off: {}", e.getMessage());
        }
        if (reader != null) {
            chatTick = e -> {
                if (e.getServer() == server) pending.drain((user, entry) -> {
                    putChat(chat, user, entry, cap);
                    OneMoreCore.LOGGER.info("Stream chat: {} ({}) waits to come in", entry.displayName(), entry.minecraftName());
                });
            };
            NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, chatTick);
        }
    }

    /** Sets the stream chat (the config is saved) and connects again. Returns a lang key suffix of the result. */
    static String configure(MinecraftServer server, String platform, String channel, String command) {
        if (!java.util.List.of("none", "twitch", "kick", "youtube").contains(platform)) return "stream.bad_platform";
        // A pasted channel link works too: its last path part is the channel.
        channel = channel.strip();
        if (platform.equals("kick") || platform.equals("twitch"))
            channel = channel.replaceFirst("^(?i)(https?://)?(www\\.)?(kick\\.com|twitch\\.tv)/", "").replaceFirst("[/?#].*$", "");
        command = command.strip();
        if (channel.length() > 64 || command.isEmpty() || command.length() > 16 || command.contains(" ")) return "stream.bad_text";
        Config.CHAT_PLATFORM.set(platform);
        Config.CHAT_CHANNEL.set(channel);
        Config.CHAT_COMMAND.set(command);
        Config.SPEC.save();
        stop();
        start(server);
        return platform.equals("none") || channel.isEmpty() ? "stream.off" : reader != null ? "stream.connected" : "stream.failed";
    }

    /** Whether a chat reader runs, and how many viewers are in the pool. */
    static boolean connected() {
        return reader != null;
    }

    static int viewers() {
        return chat.size();
    }

    static void stop() {
        if (chatTick != null) NeoForge.EVENT_BUS.unregister(chatTick);
        chatTick = null;
        if (reader != null) reader.run();
        reader = null;
        chat.clear();
    }

    /** Config names first, then chat entries. A config name is also the visible name. */
    static List<Entry> entries() {
        List<Entry> all = new ArrayList<>();
        for (String name : Config.SKIN_NAMES.get()) if (isMinecraftName(name)) all.add(new Entry(name, name));
        all.addAll(chat.values());
        return all;
    }

    /**
     * A random entry that no living customer uses; null when none is free (the customer keeps a default skin), so that two
     * customers never share a face. A chat viewer comes once: picking the entry takes it out of the pool.
     */
    static Entry pick(RandomSource random, Collection<Customer> living) {
        List<Entry> free = entries().stream().filter(e -> living.stream().noneMatch(c -> e.equals(c.identity))).toList();
        if (free.isEmpty()) return null;
        Entry e = free.get(random.nextInt(free.size()));
        chat.values().remove(e);
        return e;
    }
}
