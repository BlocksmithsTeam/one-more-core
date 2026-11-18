# One More Client

Restaurant customers for Minecraft 1.21.1 (NeoForge). Customers arrive each day, order food, wait, eat and pay.
The mod does not add food. It works with the items of any mod.

## How to play

1. Place an **Open/Closed Sign** on the floor, on a wall or under a ceiling. It shows CLOSED or OPEN and turns when the restaurant opens or closes.
2. Place a **Restaurant Computer** at most 32 blocks from the sign and use it. Open the **Manager** app and select "Edit". The camera flies through walls. Right click the first corner of the area; the box grows to where you look, and a second right click sets it. Hold Alt to point in the air (the wheel changes the distance). Keys 1, 2 and 3 change to the customer spawn point and the delivery container. Escape returns to the computer.
3. Put seats next to serving surfaces inside the area. Serving surfaces that touch each other are one table: one group sits at a table, a group takes the smallest free table that fits it, and a table rests for a moment after its group left. Sneak-click a table with an empty hand while the restaurant is closed to turn it off (customers do not use it) or on again; the Manager's tool 4 does the same.
   Place a **Menu Board** on a wall: it shows the dishes that customers can order now, by kind, and the combos.
4. Click the sign. A player alone in the area starts the day at once. With more players, a panel opens for every player inside the area: each one holds **R** (key "Get ready (hold)"). When all of them are ready, a short countdown starts the day. Hold the key again to cancel.
5. Click a seated customer to take the order. Look at an item in the speech bubble to read its tooltip.
6. Hold the ordered item and click the customer, or the surface in front of the customer.
7. The sign is the register. Sneak-click it with an empty hand to take one stack of money. Click it with money to put the money in. The computer pays shop orders from the register.
   A broken sign keeps the money, the day, the unlocks and the paid orders in the item, like a shulker box. Place it again and set the area again.
8. Customers leave a stain on the table. A dirty surface gets no customer until `dirtCleanFraction` of it is clean.

Optional [Diegesis](https://github.com/Pocky-l/diegesis) adds a camera above the table and a folded 3D cloth.
Use NeoForge 1.21.1 with Diegesis beta.11 or newer on the client and server.
Use a dirty table with an empty main hand. Pick up the cloth and drag it over the stain. The view closes by itself when the table is clean.
Without Diegesis, hold use and move the crosshair over the stain. The server checks each wipe.

Served food shows in 3D on the table where a mod gives it a block form: the plated foods of Display Delight, and food items that place a block. The speech bubble shows the items as they are.

A day is a work shift (`dayLengthTicks`, default 6000 = 5 minutes): groups keep coming while the shift lasts, faster on later days, and the panel shows the time left. The money of a day depends on how many customers the players serve. `dayLengthTicks = 0` gives the old fixed number of customers per day.

Near a restaurant, a panel at the top left shows the day, the customers left, the served and lost customers and the register.
The start and the end of a day show as a title. A customer shows a speech bubble: a "!" when it waits for a player, then the ordered items and its patience bar.

Customers can come as a group. A group sits at one table, and one table has one party at a time. A table is a set of serving surfaces that touch each other.
One click takes the orders of the whole group. Each member gets its own food. The group eats when every member has its food, pays one bill and leaves together.

## Restaurant Computer

Use a **Restaurant Computer** inside the restaurant area, or at most 32 blocks from a sign that has no area yet. The camera moves to the monitor and a desktop opens. Click an app icon to open its window; the × closes it. Escape closes the window, then leaves the computer.

- **Menu**: a searchable catalog of the restaurant's dishes, filtered by kind (main, drink, starter, dessert). Put a dish on the menu or take it off, unlock a dish with money from the register, add any food or drink with your own price (`maxDishPrice`), and change the kind of a dish with its label. One order has at most one dish of each kind. Customers order only dishes that are on the menu.
  **Combos**: 2 to 4 dishes for one price (for example a skewer, a cola and a salad). A customer orders a combo with the chance `comboChance` when all its dishes are on the menu.
- **Shop**: a searchable catalog of the items of `shop.json`. Buy pays from the register; the items arrive at the delivery container after a delay.
- **Manager**: shows the state of the restaurant and edits the area, the customer spawn point and the delivery container with a free camera (keys 1, 2, 3 choose the tool; WASD, Space and Shift fly; Ctrl flies fast). Points must be at most 48 blocks from the computer.

The order goes into this container. If the container is full or gone, the items drop there.

## Configuration

- `config/onemoreclient-common.toml`: currency item, patience, customer count, group size and chance, dirt, tip, seat heights, food display, shop delivery time, computer apps (`appMenu`, `appShop`, `appManager`).
- `lostCustomerPenalty`: money lost per customer who leaves without food. Default: 0 (off). The register stays at or above 0.
- `readyCountdownTicks`: ticks between "everyone is ready" and the start of the day, when more than one player is in the area. Default: 60. 0 starts at once.
- `maxLostPerDay`: lost customers that fail the day. Default: 0 (off). A failed day repeats; earned money stays.
- `config/onemoreclient/menu.json`: the orderable items. `/onemoreclient reload` reads it again.
- `config/onemoreclient/shop.json`: the items of the Shop app. `/onemoreclient reload` reads it again.

```json
[
  {"id": "bread", "item": "minecraft:bread", "price": 2},
  {"id": "soup", "item": "#c:foods/soup", "price": 4, "unlock": {"day": 2}},
  {"id": "pie", "item": {"id": "minecraft:pumpkin_pie", "count": 1}, "price": 6, "match": "exact", "unlock": {"day": 3, "cost": 10}}
]
```

`item` is an item id, a `#tag`, or a full item stack with components. `match` is `ignore` (default) or `exact` (components must be equal).
An entry with `cost` is bought with `/onemoreclient unlock <id>` or in the Menu app; the money comes from the register. `category` (`main`, `drink`, `starter`, `dessert`) is optional; without it the kind comes from the item.

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

`item` is an item id or a full item stack with components. `count` and `icon` (an item shown in the list) are optional.

## Customer skins and names

Without setup, customers use the default skins and have no name.

- `skinNames` in `config/onemoreclient-common.toml` is a list of Minecraft account names, for example `["Notch", "jeb_"]`. A customer gets the skin of one account. The account name is shown above the customer.
- Streamers: set `chatPlatform = "twitch"` and `chatChannel` to the channel name. A viewer writes `!omc <Minecraft name>` in the chat. A customer gets the skin of that account and the chat name of the viewer. Each viewer has one entry; a new command replaces it. `skinPoolSize` limits the number of viewers, and `chatCommand` changes the command.

The mod only reads the chat. It needs no login and no token, and it sends nothing. Restart the world or the server after you change the chat settings.
In multiplayer the chat reader runs on the server. The server owner sets it in the config of the server.
Each client downloads the skins from the Minecraft skin servers. Only Twitch is supported.

## Compatibility

Seats and serving surfaces are block tags: `onemoreclient:seats` and `onemoreclient:serving_surfaces`.
Add blocks of other mods with a datapack. Entries for several furniture mods are included.

## License

MIT. The stain texture comes from Hands On Delight (MIT), see THIRD_PARTY_NOTICES.md. The cloth is original MIT art.
