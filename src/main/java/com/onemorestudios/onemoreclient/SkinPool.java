package com.onemorestudios.onemoreclient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;

/**
 * The identities that customers can get: a Minecraft account name for the skin and a visible name.
 * Sources: the config list skinNames and the stream chat. Server thread only.
 */
public class SkinPool {
    public record Entry(String displayName, String minecraftName) {}

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final int MAX_NICK = 25;

    /** Chat entries by chat user, oldest first. */
    private static final Map<String, Entry> chat = new LinkedHashMap<>();
    /** The running chat reader: its stop method. */
    private static Runnable reader;

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
    static void addChat(String user, String displayName, String minecraftName, int cap) {
        if (!isMinecraftName(minecraftName)) return;
        String nick = cleanNick(displayName);
        chat.remove(user);
        chat.put(user, new Entry(nick.isEmpty() ? minecraftName : nick, minecraftName));
        var oldest = chat.keySet().iterator();
        while (chat.size() > Math.max(1, cap)) { oldest.next(); oldest.remove(); }
    }

    /** Starts the chat reader if the config names a channel. The server owner configures it; it runs on the server. */
    static void start(MinecraftServer server) {
        String platform = Config.CHAT_PLATFORM.get(), channel = Config.CHAT_CHANNEL.get().strip();
        if (platform.equals("none") || channel.isEmpty()) return;
        java.util.function.Consumer<TwitchChat.Message> handler = m -> {
            String name = commandName(m.text(), Config.CHAT_COMMAND.get());
            if (name != null) server.execute(() -> addChat(platform + ":" + m.user(), m.displayName(), name, Config.SKIN_POOL_SIZE.get()));
        };
        try {
            switch (platform) {
                case "twitch" -> { TwitchChat t = new TwitchChat(channel.toLowerCase(Locale.ROOT), handler); t.start(); reader = t::stop; }
                case "kick" -> { KickChat k = new KickChat(channel.toLowerCase(Locale.ROOT), handler); k.start(); reader = k::stop; }
                case "youtube" -> { YouTubeChat y = new YouTubeChat(channel, handler); y.start(); reader = y::stop; }
                default -> { }
            }
        } catch (IllegalArgumentException e) {
            OneMoreClient.LOGGER.error("Stream chat is off: {}", e.getMessage());
        }
    }

    static void stop() {
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

    /** A random entry that no living customer uses. Entries repeat when the pool is too small. Null: the pool is empty. */
    static Entry pick(RandomSource random, Collection<Customer> living) {
        List<Entry> all = entries();
        List<Entry> free = all.stream().filter(e -> living.stream().noneMatch(c -> e.equals(c.identity))).toList();
        if (!free.isEmpty()) all = free;
        return all.isEmpty() ? null : all.get(random.nextInt(all.size()));
    }
}
