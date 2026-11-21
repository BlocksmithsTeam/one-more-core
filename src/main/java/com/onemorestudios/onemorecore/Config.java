package com.onemorestudios.onemorecore;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    static final ModConfigSpec.ConfigValue<String> CURRENCY = B
            .comment("Item that customers pay with.")
            .define("currencyItem", "onemorecore:coin");
    static final ModConfigSpec.IntValue MAX_AREA = B
            .comment("Maximum restaurant area size per axis, in blocks.")
            .defineInRange("maxAreaSize", 64, 4, 256);
    static final ModConfigSpec.BooleanValue READY_CHECK = B
            .comment("All players inside the area must click the sign before the day starts.")
            .define("requireReadyCheck", true);
    static final ModConfigSpec.IntValue READY_COUNTDOWN = B
            .comment("Ticks between the moment every player is ready and the start of the day. 0 starts the day at once.")
            .defineInRange("readyCountdownTicks", 60, 0, 600);
    static final ModConfigSpec.IntValue DAY_LENGTH = B
            .comment("Length of the service of one day, in ticks (1200 = one minute). Customers come during the whole service;",
                    "their number depends on the free tables and the speed of the players. 0: a fixed number of customers per day instead.")
            .defineInRange("dayLengthTicks", 6000, 0, 1_728_000);
    static final ModConfigSpec.IntValue TABLE_PAUSE = B
            .comment("Ticks that a table stays empty after its customers left, before new customers sit there.")
            .defineInRange("tablePauseTicks", 100, 0, 72000);
    static final ModConfigSpec.DoubleValue COMBO_CHANCE = B
            .comment("Chance that a customer orders one of the restaurant's combos, when a combo can be ordered.")
            .defineInRange("comboChance", 0.3, 0, 1);
    static final ModConfigSpec.IntValue CUSTOMERS_BASE = B
            .comment("Only with dayLengthTicks = 0: customers per day = customersBase + customersPerDay * (day - 1).")
            .defineInRange("customersBase", 3, 1, 1000);
    static final ModConfigSpec.IntValue CUSTOMERS_PER_DAY = B.defineInRange("customersPerDay", 1, 0, 1000);
    static final ModConfigSpec.IntValue LOST_CUSTOMER_PENALTY = B
            .comment("Money that the register loses for each customer who leaves without food. 0 disables the penalty. The register does not go below 0.")
            .defineInRange("lostCustomerPenalty", 0, 0, 1_000_000);
    static final ModConfigSpec.IntValue MAX_LOST_PER_DAY = B
            .comment("The day fails when this many customers leave without food. 0 disables it.")
            .defineInRange("maxLostPerDay", 0, 0, 1000);
    static final ModConfigSpec.IntValue SPAWN_INTERVAL = B
            .comment("Ticks between two groups of customers, on average. It is shorter on later days, down to 40 percent, and varies by up to 40 percent.")
            .defineInRange("spawnIntervalTicks", 200, 1, 72000);
    static final ModConfigSpec.IntValue ORDER_PATIENCE = B
            .comment("Ticks a seated customer waits for a player to take the order.")
            .defineInRange("orderPatienceTicks", 2400, 20, 72000);
    static final ModConfigSpec.IntValue FOOD_PATIENCE = B
            .comment("Ticks a customer waits for the food after the order is taken.")
            .defineInRange("foodPatienceTicks", 6000, 20, 72000);
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
            .comment("Chance that the next customers come as a group. A group of 2 is twice as likely as one of 3, and so on up to maxGroupSize.")
            .defineInRange("groupChance", 0.6, 0, 1);
    static final ModConfigSpec.DoubleValue WALK_SPEED = B
            .comment("Walking speed of customers (movement speed attribute; a zombie has 0.23).")
            .defineInRange("walkSpeed", 0.17, 0.05, 1);
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
            .defineListAllowEmpty("seatHeights", defaultSeatHeights(), () -> "", Config::isPair);
    public static final ModConfigSpec.ConfigValue<List<? extends String>> FOOD_DISPLAY = B
            .comment("How a served item lies on the serving surface. Format: \"modid:item=flat\", \"modid:item=upright\" or \"modid:item=block\".",
                    "Items that are not in this list use their model: 3D items stay 3D, 2D items that place a block show that block,",
                    "other 2D items lie flat. \"block\" works only for items that place a block.")
            .defineListAllowEmpty("foodDisplay", List.of(), () -> "", Config::isPair);
    public static final ModConfigSpec.DoubleValue FOOD_SCALE = B.defineInRange("foodScale", 1.0, 0.1, 4);
    static final ModConfigSpec.ConfigValue<List<? extends String>> SKIN_NAMES = B
            .comment("Minecraft account names. Customers get the skins and the names of these accounts.",
                    "If this list and the stream chat give no names, customers use the default skins and have no name.")
            .defineListAllowEmpty("skinNames", List.of(), () -> "", o -> o instanceof String);
    static final ModConfigSpec.ConfigValue<String> CHAT_PLATFORM = B
            .comment("Stream chat that viewers use to become customers: \"none\", \"twitch\", \"kick\" or \"youtube\". The chat is only read.",
                    "chatChannel: the Twitch or Kick channel name (Kick: or the chatroom id, digits), or for YouTube a channel handle (@name) or a video id.",
                    "Kick and YouTube have no public chat API for this: the mod reads them like their web pages do, which can stop working when they change.",
                    "A viewer writes the chat command and a Minecraft account name, for example: !omc Notch",
                    "The customer gets the skin of that account and the chat name of the viewer. A restart applies changes.")
            .define("chatPlatform", "none", o -> "none".equals(o) || "twitch".equals(o) || "kick".equals(o) || "youtube".equals(o));
    static final ModConfigSpec.ConfigValue<String> CHAT_CHANNEL = B.define("chatChannel", "");
    static final ModConfigSpec.ConfigValue<String> CHAT_COMMAND = B.define("chatCommand", "!omc");
    static final ModConfigSpec.IntValue SKIN_POOL_SIZE = B
            .comment("Maximum number of chat viewers in the skin pool. The oldest viewer drops out.")
            .defineInRange("skinPoolSize", 100, 1, 10000);

    static final ModConfigSpec.IntValue MAX_DISH_PRICE = B
            .comment("Highest price that a player can give a dish of their own on the Restaurant Computer.")
            .defineInRange("maxDishPrice", 100, 1, 1_000_000);
    static final ModConfigSpec.IntValue SHOP_DELIVERY = B
            .comment("Ticks between a shop order on the Restaurant Computer and its delivery.")
            .defineInRange("shopDeliveryTicks", 600, 0, 72000);
    static final ModConfigSpec.BooleanValue APP_MENU = B
            .comment("Apps of the Restaurant Computer. A disabled app is shown as inactive.")
            .define("appMenu", true);
    static final ModConfigSpec.BooleanValue APP_SHOP = B.define("appShop", true);
    static final ModConfigSpec.BooleanValue APP_MANAGER = B.define("appManager", true);
    static final ModConfigSpec.BooleanValue APP_UPGRADES = B.define("appUpgrades", true);
    static final ModConfigSpec.BooleanValue COLLECT_PAYMENTS = B
            .comment("Customers leave their bill as coins on the table; a player collects them for the register. Off: the money goes",
                    "straight into the register.")
            .define("collectPayments", true);
    static final ModConfigSpec.ConfigValue<List<? extends Integer>> DECOR_COSTS = B
            .comment("Prices of the upgrade levels. Decor: each level gives 10 percent more patience.")
            .defineListAllowEmpty("upgradeDecorCosts", List.of(30, 60, 120), () -> 30, o -> o instanceof Integer i && i >= 0);
    static final ModConfigSpec.ConfigValue<List<? extends Integer>> DELIVERY_COSTS = B
            .comment("Faster delivery: each level makes shop deliveries 40 percent faster.")
            .defineListAllowEmpty("upgradeDeliveryCosts", List.of(40, 80), () -> 40, o -> o instanceof Integer i && i >= 0);
    static final ModConfigSpec.ConfigValue<List<? extends Integer>> BELL_COSTS = B
            .comment("Call bell: ringing a bell in the area during the service calls the next group at once (every 10 seconds at most).")
            .defineListAllowEmpty("upgradeBellCosts", List.of(50), () -> 50, o -> o instanceof Integer i && i >= 0);
    static final ModConfigSpec.ConfigValue<List<? extends String>> SCREEN_BLOCKS = B
            .comment("Televisions of other mods that can show the menu or the orders: \"<block id>=x0,y0,x1,y1,z,flip\".",
                    "The picture rectangle in the pixels of the block model when the screen faces south (+z), on the plane z; flip 1 when the",
                    "block's facing is the back of the screen. Set a television's screen with the Manager app (tool 5) of the computer.")
            .defineListAllowEmpty("screenBlocks", ScreenRect.defaults(), () -> "", Config::isPair);
    static final ModConfigSpec.BooleanValue REQUIRE_TRAY = B
            .comment("Food is served on a tray (from a tray rack): one tray per customer, all its dishes at once. False also takes single dishes from the hand.")
            .define("requireTray", true);
    static final ModConfigSpec.BooleanValue PRICE_EFFECT = B
            .comment("A price above 1.5 times a dish's worth (nutrition + saturation) makes customers order it less and tip less.")
            .define("priceEffect", true);
    static final ModConfigSpec.DoubleValue VIP_CHANCE = B
            .comment("Chance that a group of 1 or 2 is a VIP: twice the tip, 60 percent of the patience.")
            .defineInRange("vipChance", 0.1, 0, 1);
    static final ModConfigSpec.DoubleValue REGULAR_CHANCE = B
            .comment("Chance that a group of 1 or 2 is a regular, when the restaurant has favourite dishes: it orders a favourite,",
                    "has 20 percent more patience and gives 50 percent more tip.")
            .defineInRange("regularChance", 0.2, 0, 1);
    static final ModConfigSpec.DoubleValue FAMILY_CHANCE = B
            .comment("Chance that a group of 3 or more is a family: 20 percent more patience, a dessert in each order when there is one.")
            .defineInRange("familyChance", 0.7, 0, 1);

    static final ModConfigSpec SPEC = B.build();

    /** Seats of known mods that are not 0.5 high. The values are the top of the seat part in the block model. */
    private static List<String> defaultSeatHeights() {
        List<String> list = new ArrayList<>();
        for (String wood : List.of("oak", "spruce", "acacia", "bamboo", "birch", "cherry", "crimson", "dark_oak", "jungle", "mangrove", "warped")) {
            list.add("kaleidoscope_cookery:chair_" + wood + "=0.5625");
            list.add("kaleidoscope_cookery:cook_stool_" + wood + "=0.4375");
        }
        for (DyeColor color : DyeColor.values()) {
            list.add("kaleidoscope_tavern:" + color.getName() + "_bar_stool=0.9375");
            list.add("kaleidoscope_world_liquor:bar_stool_" + color.getName() + "=0.9375");
        }
        return list;
    }

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
            double height = v == null ? SEAT_HEIGHT.get() : Double.parseDouble(v);
            return Double.isFinite(height) ? height : SEAT_HEIGHT.get();
        } catch (NumberFormatException e) {
            return SEAT_HEIGHT.get();
        }
    }
}
