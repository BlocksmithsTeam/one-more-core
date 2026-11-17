# One More Client

Restaurant customers for Minecraft 1.21.1 (NeoForge). Customers arrive each day, order food, wait, eat and pay.
The mod does not add food. It works with the items of any mod.

## How to play

1. Place an **Open/Closed Sign**.
2. Click the sign with the **Restaurant Area Tool**. Click two corners to select the area. Sneak-click to set the customer spawn point.
3. Put seats next to serving surfaces inside the area.
4. Click the sign to start the day. In multiplayer, every player inside the area must click it.
5. Click a seated customer to take the order. Look at the customer to read the order.
6. Hold the ordered item and click the customer, or the surface in front of the customer.
7. The sign is the register. Sneak-click it with an empty hand to take one stack of money. Click it with money to put the money in. The computer pays shop orders from the register.
   A broken sign keeps the money, the day, the unlocks and the paid orders in the item, like a shulker box. Place it again and set the area again.
8. Customers leave a dirty spot on the table. Hold the use button with an empty hand and move the crosshair over the spot to wipe it. A dirty seat gets no customer.

Customers can come as a group. A group sits at one table, and one table has one party at a time. A table is a set of serving surfaces that touch each other.
One click takes the orders of the whole group. Each member gets its own food. The group eats when every member has its food, pays one bill and leaves together.

## Restaurant Computer

Place a **Restaurant Computer** inside the restaurant area and click it. It has three apps:

- **Menu**: shows all menu entries and unlocks an entry with money from the register.
- **Shop**: orders items with money from the register. The items arrive at the delivery point after a delay.
- **Manager**: shows the state of the restaurant.

To set the delivery point, sneak-click a container (chest, barrel) with the Restaurant Area Tool.
The order goes into this container. If the container is full or gone, the items drop there.

## Configuration

- `config/onemoreclient-common.toml`: currency item, patience, customer count, group size and chance, dirt, tip, seat heights, food display, shop delivery time, computer apps (`appMenu`, `appShop`, `appManager`).
- `lostCustomerPenalty`: money lost per customer who leaves without food. Default: 0 (off). The register stays at or above 0.
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
An entry with `cost` is bought with `/onemoreclient unlock <id>`; the money comes from the register.

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

MIT
