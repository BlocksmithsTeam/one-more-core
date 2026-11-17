package com.onemorestudios.onemoreclient;

import java.util.List;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    static final ModConfigSpec.ConfigValue<String> CURRENCY = B
            .comment("Item that customers pay with.")
            .define("currencyItem", "minecraft:emerald");
    static final ModConfigSpec.IntValue MAX_AREA = B
            .comment("Maximum restaurant area size per axis, in blocks.")
            .defineInRange("maxAreaSize", 64, 4, 256);
    static final ModConfigSpec.BooleanValue READY_CHECK = B
            .comment("All players inside the area must click the sign before the day starts.")
            .define("requireReadyCheck", true);
    static final ModConfigSpec.IntValue CUSTOMERS_BASE = B
            .comment("Customers per day = customersBase + customersPerDay * (day - 1).")
            .defineInRange("customersBase", 3, 1, 1000);
    static final ModConfigSpec.IntValue CUSTOMERS_PER_DAY = B.defineInRange("customersPerDay", 1, 0, 1000);
    static final ModConfigSpec.IntValue SPAWN_INTERVAL = B
            .comment("Ticks between two customers.")
            .defineInRange("spawnIntervalTicks", 200, 1, 72000);
    static final ModConfigSpec.IntValue ORDER_PATIENCE = B
            .comment("Ticks a seated customer waits for a player to take the order.")
            .defineInRange("orderPatienceTicks", 1200, 20, 72000);
    static final ModConfigSpec.IntValue FOOD_PATIENCE = B
            .comment("Ticks a customer waits for the food after the order is taken.")
            .defineInRange("foodPatienceTicks", 2400, 20, 72000);
    static final ModConfigSpec.IntValue EAT_TICKS = B.defineInRange("eatTicks", 200, 0, 72000);
    static final ModConfigSpec.IntValue MAX_ORDER_ITEMS = B.defineInRange("maxOrderItems", 2, 1, 6);
    static final ModConfigSpec.IntValue MAX_TIP = B
            .comment("Maximum tip as a percentage of the order price. The tip scales with the patience that is left.")
            .defineInRange("maxTipPercent", 50, 0, 1000);
    static final ModConfigSpec.IntValue WRONG_PENALTY = B
            .comment("Patience ticks a customer loses when a player offers a wrong item. 0 disables the penalty.")
            .defineInRange("wrongItemPenaltyTicks", 100, 0, 72000);
    static final ModConfigSpec.IntValue MAX_GROUP = B
            .comment("Largest group of customers that sit together at one table. 1 disables groups.")
            .defineInRange("maxGroupSize", 4, 1, 16);
    static final ModConfigSpec.DoubleValue GROUP_CHANCE = B
            .comment("Chance that the next customers come as a group of 2 to maxGroupSize.")
            .defineInRange("groupChance", 0.4, 0, 1);
    static final ModConfigSpec.BooleanValue DIRT_ENABLED = B
            .comment("Customers leave a dirty spot on the serving surface. A dirty spot gets no customer until a player wipes it.")
            .define("dirtEnabled", true);
    static final ModConfigSpec.DoubleValue DIRT_CLEAN_FRACTION = B
            .comment("A dirty spot becomes fully clean when this fraction of it is wiped.")
            .defineInRange("dirtCleanFraction", 0.8, 0, 1);
    static final ModConfigSpec.DoubleValue SEAT_HEIGHT = B
            .comment("Default height of the sitting surface above the bottom of a seat block.")
            .defineInRange("seatHeight", 0.5, 0, 2);
    static final ModConfigSpec.ConfigValue<List<? extends String>> SEAT_HEIGHTS = B
            .comment("Sitting height for specific blocks. Format: \"modid:block=0.5\".")
            .defineListAllowEmpty("seatHeights", List.of(), () -> "", Config::isPair);
    public static final ModConfigSpec.ConfigValue<List<? extends String>> FOOD_DISPLAY = B
            .comment("How a served item lies on the serving surface. Format: \"modid:item=flat\" or \"modid:item=upright\".",
                    "Items that are not in this list use their model: 2D items lie flat, 3D items stay 3D.")
            .defineListAllowEmpty("foodDisplay", List.of(), () -> "", Config::isPair);
    public static final ModConfigSpec.DoubleValue FOOD_SCALE = B.defineInRange("foodScale", 1.0, 0.1, 4);

    static final ModConfigSpec SPEC = B.build();

    private static boolean isPair(Object o) {
        return o instanceof String s && s.indexOf('=') > 0;
    }

    /** Value of "key=value" in the list, or null. */
    public static String lookup(List<? extends String> list, ResourceLocation key) {
        String prefix = key + "=";
        for (String s : list) if (s.startsWith(prefix)) return s.substring(prefix.length());
        return null;
    }

    public static Item currency() {
        ResourceLocation id = ResourceLocation.tryParse(CURRENCY.get());
        Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
        return item == Items.AIR ? Items.EMERALD : item;
    }

    static double seatHeight(Block block) {
        String v = lookup(SEAT_HEIGHTS.get(), BuiltInRegistries.BLOCK.getKey(block));
        try {
            return v == null ? SEAT_HEIGHT.get() : Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return SEAT_HEIGHT.get();
        }
    }
}
