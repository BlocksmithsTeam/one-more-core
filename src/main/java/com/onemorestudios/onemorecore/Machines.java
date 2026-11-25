package com.onemorestudios.onemorecore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.fml.loading.FMLPaths;

/**
 * The machines of each recipe type: config/onemorecore/machines.json, {"recipe type": ["block", ...]}. One of the blocks
 * in the restaurant area is enough. JEI fills the file: a client with JEI sends the machines it lists, and an operator's
 * (or the singleplayer owner's) list adds the types and blocks the file does not have yet. A type the file does not name
 * uses the block with the same id, else it needs no machine.
 */
public final class Machines {
    private Machines() {}

    static final String FILE = "machines.json";
    static final Map<String, List<String>> DEFAULTS = Map.of(
            "minecraft:crafting", List.of("minecraft:crafting_table"),
            "minecraft:smelting", List.of("minecraft:furnace"),
            "minecraft:smoking", List.of("minecraft:smoker"),
            "minecraft:blasting", List.of("minecraft:blast_furnace"),
            "minecraft:campfire_cooking", List.of("minecraft:campfire", "minecraft:soul_campfire"),
            "minecraft:stonecutting", List.of("minecraft:stonecutter"),
            "minecraft:smithing", List.of("minecraft:smithing_table"));

    private static volatile Map<ResourceLocation, Set<ResourceLocation>> byType;
    private static final Set<ResourceLocation> UNKNOWN_LOGGED = ConcurrentHashMap.newKeySet();

    /** The blocks of which one must be in the area to make recipes of this type; empty when none is needed. */
    public static Set<ResourceLocation> forType(ResourceLocation type) {
        if (type == null) return Set.of();
        if (byType == null) reload(false);
        Set<ResourceLocation> known = byType.get(type);
        if (known != null) return known;
        if (BuiltInRegistries.BLOCK.containsKey(type)) return Set.of(type);
        if (UNKNOWN_LOGGED.add(type)) OneMoreCore.LOGGER.info("No machine known for recipe type {}; its recipes need none", type);
        return Set.of();
    }

    /** Reads the file, or (write) writes the defaults when it is missing. A broken file falls back to the defaults. */
    static synchronized void reload(boolean write) {
        Path path = path();
        Map<ResourceLocation, Set<ResourceLocation>> map = parse(toJson(DEFAULTS));
        try {
            if (Files.exists(path)) map = parse(Files.readString(path));
            else if (write) save(map);
        } catch (IOException | RuntimeException e) {
            OneMoreCore.LOGGER.warn("{}: {}; the default machines are used", path, e.toString());
        }
        byType = map;
    }

    /** {"type": ["block", ...]}. An entry that is no id or no list of ids is left out. */
    public static Map<ResourceLocation, Set<ResourceLocation>> parse(String json) {
        Map<ResourceLocation, Set<ResourceLocation>> map = new HashMap<>();
        for (var entry : JsonParser.parseString(json).getAsJsonObject().entrySet()) {
            ResourceLocation type = ResourceLocation.tryParse(entry.getKey());
            if (type == null || !entry.getValue().isJsonArray()) continue;
            Set<ResourceLocation> blocks = new HashSet<>();
            for (var block : entry.getValue().getAsJsonArray()) {
                ResourceLocation id = block.isJsonPrimitive() ? ResourceLocation.tryParse(block.getAsString()) : null;
                if (id != null) blocks.add(id);
            }
            map.put(type, Set.copyOf(blocks));
        }
        return map;
    }

    /** The file's map with every type and block of found added. Only registered recipe types and blocks are taken. */
    public static Map<ResourceLocation, Set<ResourceLocation>> merge(Map<ResourceLocation, Set<ResourceLocation>> current,
            Map<ResourceLocation, ? extends Iterable<ResourceLocation>> found) {
        Map<ResourceLocation, Set<ResourceLocation>> merged = new HashMap<>();
        current.forEach((type, blocks) -> merged.put(type, new HashSet<>(blocks)));
        found.forEach((type, blocks) -> {
            if (!BuiltInRegistries.RECIPE_TYPE.containsKey(type)) return;
            for (ResourceLocation block : blocks) {
                if (BuiltInRegistries.BLOCK.containsKey(block)) merged.computeIfAbsent(type, key -> new HashSet<>()).add(block);
            }
        });
        Map<ResourceLocation, Set<ResourceLocation>> result = new HashMap<>();
        merged.forEach((type, blocks) -> result.put(type, Set.copyOf(blocks)));
        return result;
    }

    /** Adds what a trusted client's JEI lists; true when the file changed. */
    static synchronized boolean add(Map<ResourceLocation, List<ResourceLocation>> found) {
        if (byType == null) reload(true);
        var merged = merge(byType, found);
        if (merged.equals(byType)) return false;
        byType = merged;
        try {
            save(merged);
        } catch (IOException e) {
            OneMoreCore.LOGGER.warn("{}: {}", path(), e.toString());
        }
        return true;
    }

    /** The machine blocks of wanted that stand in the area, or null when a part of the area is not loaded. */
    static Set<ResourceLocation> scan(ServerLevel level, BoundingBox area, Set<ResourceLocation> wanted) {
        Set<Block> blocks = new HashSet<>();
        for (ResourceLocation id : wanted) BuiltInRegistries.BLOCK.getOptional(id).ifPresent(blocks::add);
        Set<ResourceLocation> found = new TreeSet<>();
        if (blocks.isEmpty()) return found;
        int minSection = Math.max(level.getMinSection(), SectionPos.blockToSectionCoord(area.minY()));
        int maxSection = Math.min(level.getMaxSection() - 1, SectionPos.blockToSectionCoord(area.maxY()));
        for (int cx = SectionPos.blockToSectionCoord(area.minX()); cx <= SectionPos.blockToSectionCoord(area.maxX()); cx++) {
            for (int cz = SectionPos.blockToSectionCoord(area.minZ()); cz <= SectionPos.blockToSectionCoord(area.maxZ()); cz++) {
                if (!level.hasChunk(cx, cz)) return null;
                var chunk = level.getChunk(cx, cz);
                for (int sy = minSection; sy <= maxSection; sy++) {
                    var section = chunk.getSection(level.getSectionIndexFromSectionY(sy));
                    // The palette tells quickly whether a section can hold one of the blocks.
                    if (section.hasOnlyAir() || !section.getStates().maybeHas(state -> blocks.contains(state.getBlock()))) continue;
                    int x0 = Math.max(area.minX(), cx << 4), x1 = Math.min(area.maxX(), (cx << 4) + 15);
                    int y0 = Math.max(area.minY(), sy << 4), y1 = Math.min(area.maxY(), (sy << 4) + 15);
                    int z0 = Math.max(area.minZ(), cz << 4), z1 = Math.min(area.maxZ(), (cz << 4) + 15);
                    for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) {
                        BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                        if (blocks.contains(state.getBlock())) found.add(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
                    }
                }
            }
        }
        return found;
    }

    private static Path path() { return FMLPaths.CONFIGDIR.get().resolve(OneMoreCore.MODID).resolve(FILE); }

    private static void save(Map<ResourceLocation, Set<ResourceLocation>> map) throws IOException {
        Map<String, List<String>> sorted = new TreeMap<>();
        map.forEach((type, blocks) -> sorted.put(type.toString(), blocks.stream().map(ResourceLocation::toString).sorted().toList()));
        Path path = path();
        Files.createDirectories(path.getParent());
        Files.writeString(path, toJson(sorted));
    }

    private static String toJson(Map<String, List<String>> map) {
        JsonObject json = new JsonObject();
        new TreeMap<>(map).forEach((type, blocks) -> {
            JsonArray array = new JsonArray();
            blocks.forEach(array::add);
            json.add(type, array);
        });
        return new GsonBuilder().setPrettyPrinting().create().toJson(json);
    }

    /** Client to server: the machines JEI lists for each recipe type. */
    public record Found(Map<ResourceLocation, List<ResourceLocation>> types) implements CustomPacketPayload {
        public static final Type<Found> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "machines"));
        static final StreamCodec<ByteBuf, Found> CODEC = ByteBufCodecs.<ByteBuf, ResourceLocation, List<ResourceLocation>, Map<ResourceLocation, List<ResourceLocation>>>map(
                HashMap::new, ResourceLocation.STREAM_CODEC, ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs.list(64)), 4096)
                .map(Found::new, Found::types);

        /** Only an operator or the singleplayer owner adds to the file; the recipe index is built again when it changed. */
        static void handle(Found found, ServerPlayer player) {
            var server = player.getServer();
            if (server == null || !player.hasPermissions(2) && !server.isSingleplayerOwner(player.getGameProfile())) return;
            if (add(found.types)) Ingredients.rebuild(server);
        }

        @Override
        public Type<Found> type() { return TYPE; }
    }
}
