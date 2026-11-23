# One More Core

Restaurant customers for Minecraft 1.21.1 (NeoForge). Customers arrive each day, order food, wait, eat and pay.
The mod does not add food. It works with the items of any mod.

## How to play

1. Place an **Open/Closed Sign** on the floor, on a wall or under a ceiling. It shows CLOSED or OPEN and turns when the restaurant opens or closes.
2. Place a **Restaurant Computer** where it will be inside the restaurant area. Before setting the area, you can use a computer at most 32 blocks from the sign in each direction. Open the **Manager** app and select "Edit". The camera flies through walls. Press Use to set the first corner of the area. The box grows to where you look, and a second press sets it. Hold Alt to point in the air (the wheel changes the distance). Keys 1, 2 and 3 change to the customer spawn point and the delivery container. Escape returns to the computer.
3. Put seats next to serving surfaces inside the area. Serving surfaces that touch each other are one table: one group sits at a table, a group takes the smallest free table that fits it, and a table rests for a moment after its group left. Sneak-click a table with an empty hand while the restaurant is closed to turn it off (customers do not use it) or on again; the Manager's tool 4 does the same.
   Place a **Menu Screen** on a wall: a television that shows the dishes that customers can order now, by kind, and the combos, like the menu over a fast food counter. Look up while you place a screen to hang it turned down (22.5 or 45 degrees); sneak-click it with an empty hand to set the angle, mode, menu categories, text size and automatic paging. TVs occupy three blocks across and two high. A Monitor occupies one block. Matching screens in a rectangle share one picture.
4. Click the sign. A player alone in the area starts the day at once. With more players, the ready panel opens for the players inside. Clicking the sign from outside can also start the check for them. The panel follows the restaurant you are in. Hold **R** (key "Get ready (hold)") to get ready. Hold **R** again to withdraw your ready status before the countdown starts. When everyone is ready, a short countdown starts the day. Ready input is locked during the countdown.
5. Click a seated customer to take the order. Look at an item in the speech bubble to read its tooltip.
6. Hold the ordered item and click the customer, or the surface in front of the customer.
7. Serve on a **Tray**. Take one from a **Tray Rack** and put it on a counter. Click a spot on the tray with each dish of one customer. It holds up to three dishes, and they keep their positions and angles when you pick up the tray or set it down. The dishes sway slightly as you walk. A taken order shows as a card on the **Order Screen** (a television: table number, dishes, patience and price). Look at a dish on a screen to read its tooltip. Click a visible card within reach to get a ticket for an active order. Click the tray with the ticket to show the table number. With Diegesis, click a Menu Screen or Order Screen with an empty hand to open the close-up. Use the mouse to point at dishes and click order cards there.
   Sneak-click the tray with an empty hand to take it, then click the customer (or the table in front of the customer) to serve every dish of that customer on the tray at once. With Diegesis, click a tray with an empty hand to open it up close. Drag dishes freely around the tray. A translucent 3D preview shows how each dish will rest where you point. You need permission to use the tray block to open the view or move dishes. Replacing the tray closes the view. `requireTray = false` also takes single dishes from the hand.
   Place a Table Number card where you want its label. Enter up to two characters. The order screen and tray tickets use this label. Each guest has a separate row on the order screen.
   A party with a bill above zero leaves **Coins** on the table. Click the table with an empty hand to put the coins in the register. With Diegesis, use any connected surface of the table with an empty hand to open the close-up, then click the coins. Coins left over when the register fills stay on the table. A table with coins gets no new party. `collectPayments = false` pays straight into the register. Any excess from a direct payment drops at the sign.
   After a restart, dishes already served to an interrupted party become a bill on its table. If the table is gone, the bill goes to the register and any excess drops at the sign.
8. The sign is the register. Sneak-click it with an empty hand to take one stack of money. Click it with money to put the money in. The computer pays shop orders from the register.
   Breaking the sign clears the coins from the tables and puts their value in the dropped sign's register. Any excess drops at the sign as currency. The sign item keeps the money, the day, the ingredient unlocks and the paid orders, like a shulker box. Place it again and set the area again.
9. A party leaves stains where it ate; a stain can go over the joins of a table's blocks. A table is one thing to clean: it gets no customer until `dirtCleanFraction` of all its dirt is wiped, then all of it is clean. Cleaning progress survives a restart.
10. A **Menu Book** on a table shows the menu on pages. With Diegesis it opens over the table using the Hands On Delight recipe book, with a tab for each kind, a page for each dish (a combo shows its dishes) with its price, search and page turning. Without Diegesis it opens as a book screen.
    Menu Screens and Menu Books show up to 256 dishes and combos in total, with space for available combos. They show each dish's item variant.
11. Televisions of other mods can show the menu or the orders too. In the Manager's free camera, tool 5 (Screens) clicks a television: off, menu, orders. The televisions of MrCrayfish's Refurbished Furniture and Yuushya Townscape work without a config line; `screenBlocks` adds others.
    Players approaching the televisions see the current menu or orders. Switching off the last configured television clears its picture for players who saw it, even if they have moved away.

Optional [Diegesis](https://github.com/Pocky-l/diegesis) adds a camera above the table and a folded 3D cloth.
Use NeoForge 1.21.1 with Diegesis 1.0.0-beta.15 or newer on the client and server.
Use a dirty table with an empty main hand. Pick up the cloth and drag it over the stain. The view closes by itself when the table is clean.
Without Diegesis, hold use and move the crosshair over the stain. The server checks each wipe.

Served food shows in 3D on the table where a mod gives it a block form: the plated foods of Display Delight, and food items that place a block. The speech bubble shows the items as they are.

A day is a work shift (`dayLengthTicks`, default 6000 = 5 minutes): groups keep coming while the shift lasts, faster on later days, and the panel shows the time left. The money of a day depends on how many customers the players serve. `dayLengthTicks = 0` gives the old fixed number of customers per day. A fixed-count shift needs an enabled table with seats to start. If service loses every enabled table with seats, the day ends with the results earned so far.

Near a restaurant, a panel at the top left shows the day, the customers left, the served and lost customers and the register.
The start and the end of a day show as a title. A customer shows a speech bubble: a "!" when it waits for a player, then the ordered items and its patience bar.

Some parties are special guests, with a mark on the speech bubble: a **VIP** (star) waits less and tips more, a **regular** (heart) waits longer and often orders a dish it had before, a **family** (house) is a group of three or more that orders a dessert too (`vipChance`, `regularChance`, `familyChance`).
A price above 1.5 times the worth of a dish (nutrition + saturation) makes customers order it less often and tip less for it (`priceEffect`).

Customers can come as a group. A group sits at one table, and one table has one party at a time. A table is a set of serving surfaces that touch each other.
If you join tables during service, new parties cannot reserve the connected table while it is occupied.
One click takes the orders of the whole group. Each member gets its own food. The group eats when every member has its food, pays one bill and leaves together.

## Restaurant Computer

Use a **Restaurant Computer** inside the restaurant area. Before setting the area, you can use a computer at most 32 blocks from the sign in each direction. The camera moves to the monitor, the monitor boots (a power-on line, the boot text, the logo; a click or a key skips it) and the desktop opens. You need permission to use the computer block. Stay within reach of it while using its apps. Click an app icon to open its window; the × closes it. Escape closes the window, then turns the monitor off and leaves the computer.

- The **Menu** app lists the restaurant's dishes and filters them by kind (main, drink, starter, dessert). Put a dish on the menu or take it off, add any food or drink with your own price (`maxDishPrice`), and change the kind of a dish with its label. Recipe-discovered dishes start at their food value and can be repriced per restaurant. Recipes from Minecraft and installed mods add foods and drinks to the catalog. Customers can order an enabled dish when all ingredients in at least one recipe are available. An item tag accepts any one of its items. Tools and containers such as bowls are free, and the recipe check follows up to three levels of intermediate ingredients. For example, sugar cane makes sugar available for pumpkin pie.
  A new restaurant starts with no purchased ingredients and receives 60 coins once (`startingBalance`). Use `/onemorecore unlock <item id>` inside the restaurant to buy a raw ingredient from the register, for example `/onemorecore unlock minecraft:wheat`. Breaking and re-placing the sign keeps the existing balance and unlocks. Existing saves keep access to their previously available dishes through a one-time migration.
  **Combos** offer 2 to 3 places for one price. A place is one dish or a whole kind ("Any drink"), for example a burger + any drink + any starter (for example a skewer, a cola and a salad). A customer orders a combo with the chance `comboChance` when every place can be filled with an orderable dish. The Menu app marks saved four-place combos unavailable until you edit them down to two or three places.
- The **Shop** app lists the items from `shop.json` and the ingredients the restaurant has unlocked. Each ingredient listing delivers 16 items or one full stack if its limit is smaller. Add items to the cart, change the counts in the cart, and check out. The register pays for the whole cart at once. If money is missing, the cart shows how much. Checkout allows up to 32 listings and 1,024 characters of cart data. If checkout fails, the Shop keeps the cart and shows the reason. If prices change, review the updated total and check out again.
- **Upgrades**: Decor (guests wait longer), Fast delivery (shop orders arrive sooner) and Call bell (use a Minecraft bell in the area while the day runs: the next group comes now). The prices are `upgradeDecorCosts`, `upgradeDeliveryCosts` and `upgradeBellCosts`.
- **Manager**: shows the state of the restaurant and edits the area, the customer spawn point and the delivery container with a free camera (keys 1, 2, 3 choose the tool, 4 turns tables off, 5 sets televisions; WASD, Space and Shift fly; Ctrl flies fast). Each press of Use applies one edit. Points must be at most 48 blocks from the computer.

Shop orders arrive after a delay at the delivery point set at checkout. Large orders can arrive in batches. If the container is full or gone, the items drop there.

## Configuration

- `config/onemorecore-common.toml`: currency item, patience, customer count, group size and chance, dirt, tip, seat heights, food display, shop delivery time, computer apps (`appMenu`, `appShop`, `appManager`, `appUpgrades`). The default currency is the mod's coin. Setting `currencyItem` to `onemorecore:tray`, `onemorecore:iron_tray` or `onemorecore:ticket` also uses the mod's coin.
- `lostCustomerPenalty`: money lost per customer who leaves without food. Default: 0 (off). The register stays at or above 0.
- `readyCountdownTicks`: ticks between "everyone is ready" and the start of the day, when more than one player is in the area. Default: 60. 0 starts at once.
- `maxLostPerDay`: lost customers that fail the day. Default: 0 (off). A failed day repeats; earned money stays.
- `screenBlocks`: televisions of other mods, `"mod:block=x0,y0,x1,y1,z,flip"`: the picture's corners and plane in model pixels of the block facing south, and `flip = 1` when the picture is on the side opposite to the block's facing.
- `config/onemorecore/menu.json` supplies additional dishes and sets their prices, kinds and item variants. The old `unlock` field is deprecated and only read for old-save migration.
- `config/onemorecore/ingredients.json` is created with defaults on the first server start. It overrides ingredient prices, free and ignored items, and dish recipes. `/onemorecore reload` rereads both files and rebuilds the recipe index. The index also updates after a datapack reload.
- `config/onemorecore/shop.json`: the items of the Shop app. `/onemorecore reload` reads it again.

```json
[
  {"id": "bread", "item": "minecraft:bread", "price": 2},
  {"id": "soup", "item": "#c:foods/soup", "price": 4, "category": "starter"},
  {"id": "pie", "item": {"id": "minecraft:pumpkin_pie", "count": 1}, "price": 6, "match": "exact"}
]
```

`item` is an item id, a `#tag`, or a full item stack with components. `match` is `ignore` (default) or `exact` (components must be equal).
The optional `category` sets the kind (`main`, `drink`, `starter` or `dessert`). Otherwise the kind comes from the item. A configured or custom dish with no exposed recipe is available without ingredient purchases unless `ingredients.json` supplies its recipe.

The default unlock cost is 4 coins plus 2 per dish the ingredient helps make and 15% of those dishes' total worth (nutrition plus rounded saturation). The result is rounded and clamped to 5–40 coins. The shop price is one fifth of the unlock cost, rounded to at least 1 coin per bundle. The `prices` object accepts a numeric unlock price or an object with `unlock` and `shop` prices. Exact item prices take precedence over tag prices. The `free` list supplies containers and tools that are always available. The `ignore` list excludes items from ingredient purchases and recipe alternatives. Each `dishes` override supplies one recipe with one slot per array entry, where an item tag offers alternatives for that slot.

For example, `ingredients.json` can contain

```json
{
  "prices": {"minecraft:wheat": 8, "#c:crops/tomato": {"unlock": 12, "shop": 3}},
  "free": ["minecraft:bowl", "minecraft:glass_bottle", "minecraft:bucket", "minecraft:water_bucket", "minecraft:stick", "#c:tools/knife"],
  "ignore": [],
  "dishes": {"minecraft:mushroom_stew": ["minecraft:bowl", "minecraft:red_mushroom", "minecraft:brown_mushroom"]}
}
```

Kaleidoscope Cookery chairs, stools, benches and tables use the furniture tags shipped with this mod.
Kaleidoscope Tavern tables and bar counters also work as serving surfaces.
A customer blocks player interaction with its occupied seat. Free seats keep their normal furniture interaction.
For Kaleidoscope food, the `kaleidoscope_cookery:quality` component uses names such as `excellent`, not numbers.

`shop.json`:

```json
[
  {"id": "wheat", "item": "minecraft:wheat", "count": 16, "price": 4},
  {"id": "box", "item": {"id": "minecraft:shulker_box", "components": {"minecraft:container": [{"slot": 0, "item": {"id": "minecraft:egg", "count": 16}}]}}, "icon": "minecraft:egg", "price": 12}
]
```

`item` is an item id or a full item stack with components. `count` and `icon` (an item shown in the list) are optional. The shop clamps each listing's count to 1–1,728 when it loads `shop.json`.

## Customer skins and names

Without setup, customers use the default skins and have no name.

- `skinNames` in `config/onemorecore-common.toml` is a list of Minecraft account names, for example `["Notch", "jeb_"]`. A customer gets the skin of one account. The account name is shown above the customer.
- Streamers: set `chatPlatform` to `"twitch"`, `"kick"` or `"youtube"` and `chatChannel` to the channel name (YouTube: the channel handle, `@name`, or the video id of the stream). Kick and YouTube have no public chat API for this; the mod reads them like their web pages do, which can stop working when they change. A viewer writes `!omc <Minecraft name>` in the chat. A customer gets the skin of that account and the chat name of the viewer. Each viewer has one entry; a new command replaces it. `skinPoolSize` limits the number of viewers, and `chatCommand` changes the command.

The mod only reads the chat. It needs no login and no token, and it sends nothing. Restart the world or the server after you change the chat settings.
In multiplayer the chat reader runs on the server. The server owner sets it in the config of the server.
During busy chats, commands can take longer to apply. Only the latest pending command from each viewer is kept. The server holds up to twice `skinPoolSize` pending commands and drops the oldest when that fills up.
If YouTube chat keeps failing, the wait between retries grows to one minute. A successful chat check resets the delay.
Each client downloads the skins from the Minecraft skin servers.

## Compatibility

Seats and serving surfaces are block tags: `onemorecore:seats` and `onemorecore:serving_surfaces`.
Add blocks of other mods with a datapack. Entries for several furniture mods are included.

## License

MIT. The stain and the cloth are original MIT art.
The app icons of the Restaurant Computer are original pixel art (`art/computer/make_icons.py`).
The television of the Menu Screen and the Order Screen is from [Yuushya Townscape](https://github.com/XiLaiTL/Yuushya-Townscape) (MIT). The Menu Book's Diegesis close-up uses the animated recipe book from [Hands On Delight](https://github.com/Pocky-l/handson-delight) (MIT). See `THIRD_PARTY_NOTICES.md`.
