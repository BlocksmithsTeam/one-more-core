package com.onemorestudios.onemorecore.gametest;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.LongConsumer;

import com.onemorestudios.onemorecore.KickChat;
import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.SkinPool;
import com.onemorestudios.onemorecore.TwitchChat;
import com.onemorestudios.onemorecore.YouTubeChat;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(OneMoreCore.MODID)
@PrefixGameTestTemplate(false)
public final class ChatGameTests {
    /** EXTERNAL-10: A burst coalesces by viewer, respects the pending cap and drains eight commands per tick. */
    @GameTest(template = "empty")
    public static void chatBurstIsBounded(GameTestHelper helper) throws ReflectiveOperationException {
        Class<?> type = Class.forName(SkinPool.class.getName() + "$PendingChat");
        var constructor = type.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        Object pending = constructor.newInstance(6);
        Method accept = method(type, "accept", String.class, String.class, TwitchChat.Message.class);
        Method drain = method(type, "drain", BiConsumer.class);
        for (int i = 0; i < 15; i++) {
            accept.invoke(pending, "twitch", "!omc", new TwitchChat.Message("viewer" + i, "Viewer " + i, "!omc Skin" + i));
        }
        for (int i = 0; i < 100; i++) {
            accept.invoke(pending, "twitch", "!omc", new TwitchChat.Message("viewer0", "Returning viewer", "!omc NewSkin" + i));
        }

        var applied = new LinkedHashMap<String, SkinPool.Entry>();
        BiConsumer<String, SkinPool.Entry> apply = (viewer, entry) -> {
            helper.assertTrue(applied.put(viewer, entry) == null, "A queued viewer is applied only once");
        };
        drain.invoke(pending, apply);
        helper.assertValueEqual(List.copyOf(applied.keySet()), List.of("twitch:viewer4", "twitch:viewer5",
                "twitch:viewer6", "twitch:viewer7", "twitch:viewer8", "twitch:viewer9", "twitch:viewer10",
                "twitch:viewer11"), "first tick applies eight of the newest pending viewers in order");
        drain.invoke(pending, apply);
        helper.assertValueEqual(applied.size(), 12, "total commands applied from the burst");
        helper.assertValueEqual(applied.get("twitch:viewer0"), new SkinPool.Entry("Returning viewer", "NewSkin99"),
                "returning viewer's latest command");
        drain.invoke(pending, apply);
        helper.assertValueEqual(applied.size(), 12, "pending commands after draining the burst");
        helper.succeed();
    }

    /** EXTERNAL-11: Kick bootstrap reads both strings across tabs and newlines in the JSON response. */
    @GameTest(template = "empty")
    public static void kickBootstrapAcceptsJsonWhitespace(GameTestHelper helper) throws ReflectiveOperationException {
        Method parse = method(KickChat.class, "parseConnection", String.class);
        String fields = "\"app_key\"\t:\n\"abc123\", \"cluster\"\n:\t\"us-2\"";
        String flat = "{" + fields + "}";
        String wrapped = "{\"data\":{\"connections\":[{\"provider\":\"pusher\",\"credentials\":{" + fields + "}}]}}";
        for (String body : List.of(flat, wrapped)) {
            Object connection = parse.invoke(null, body);
            helper.assertTrue(connection != null, "Kick bootstrap returned Pusher credentials");
            helper.assertValueEqual(method(connection.getClass(), "appKey").invoke(connection), "abc123", "Kick app key");
            helper.assertValueEqual(method(connection.getClass(), "cluster").invoke(connection), "us-2", "Kick cluster");
        }
        helper.assertTrue(parse.invoke(null, "{\"app_key\":123,\"cluster\":\"us2\"}") == null, "Kick rejects non-string keys");
        helper.assertTrue(parse.invoke(null, "{\"app_key\":") == null, "Kick rejects malformed JSON");
        helper.succeed();
    }

    /** EXTERNAL-12: YouTube retry delays grow across page reloads and reset after a complete poll. */
    @GameTest(template = "empty")
    public static void youtubeBackoffSurvivesBootstrap(GameTestHelper helper) throws ReflectiveOperationException {
        Class<?> senderType = Class.forName(YouTubeChat.class.getName() + "$RequestSender");
        List<String> requests = new ArrayList<>();
        Object sender = Proxy.newProxyInstance(senderType.getClassLoader(), new Class<?>[] {senderType}, (proxy, method, args) -> {
            HttpRequest request = (HttpRequest) args[0];
            String path = request.uri().getPath();
            requests.add(path);
            if (path.equals("/live_chat")) {
                return "{\"INNERTUBE_API_KEY\":\"key\",\"INNERTUBE_CLIENT_VERSION\":\"version\",\"continuation\":\"first\"}";
            }
            if (requests.size() == 16) {
                return "{\"continuationContents\":{\"liveChatContinuation\":{\"continuations\":[]}}}";
            }
            if (requests.size() == 18) {
                return "{\"continuationContents\":{\"liveChatContinuation\":{\"continuations\":["
                        + "{\"timedContinuationData\":{\"continuation\":\"next\",\"timeoutMs\":5000}}]}}}";
            }
            throw new IllegalStateException("HTTP 503");
        });
        YouTubeChat reader = new YouTubeChat("abcdefghijk", message -> {});
        List<Long> delays = new ArrayList<>();
        List<Long> expected = List.of(1000L, 2000L, 4000L, 8000L, 16000L, 32000L, 60000L, 60000L, 5000L, 1000L);
        try {
            method(YouTubeChat.class, "run", senderType, LongConsumer.class).invoke(reader, sender, (LongConsumer) millis -> {
                delays.add(millis);
                if (delays.size() == expected.size()) reader.stop();
            });
        } finally {
            reader.stop();
        }
        helper.assertValueEqual(requests.stream().filter("/live_chat"::equals).count(), 9L, "chat page reloads");
        helper.assertValueEqual(requests.size(), 19, "bootstrap and poll request count");
        helper.assertValueEqual(delays, expected, "poll and retry delays recorded without sleeping");
        helper.succeed();
    }

    // The production helpers are package-private and the GameTests live in a separate package.
    private static Method method(Class<?> type, String name, Class<?>... parameters) throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }
}
