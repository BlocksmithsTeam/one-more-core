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
7. Sneak-click the sign with an empty hand to take the money from the register.

## Restaurant Computer

Place a **Restaurant Computer** inside the restaurant area and click it. It has three apps:

- **Menu**: shows all menu entries and unlocks an entry with money from the register.
- **Shop**: orders items with money from the register. The items arrive at the delivery point after a delay.
- **Manager**: shows the state of the restaurant.

To set the delivery point, sneak-click a container (chest, barrel) with the Restaurant Area Tool.
The order goes into this container. If the container is full or gone, the items drop there.

## Configuration

- `config/onemoreclient-common.toml`: currency item, patience, customer count, tip, seat heights, food display, shop delivery time, computer apps (`appMenu`, `appShop`, `appManager`).
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

`shop.json`:

```json
[
  {"id": "wheat", "item": "minecraft:wheat", "count": 16, "price": 4},
  {"id": "box", "item": {"id": "minecraft:shulker_box", "components": {"minecraft:container": [{"slot": 0, "item": {"id": "minecraft:egg", "count": 16}}]}}, "icon": "minecraft:egg", "price": 12}
]
```

`item` is an item id or a full item stack with components. `count` and `icon` (an item shown in the list) are optional.

## Compatibility

Seats and serving surfaces are block tags: `onemoreclient:seats` and `onemoreclient:serving_surfaces`.
Add blocks of other mods with a datapack. Entries for several furniture mods are included.

## License

MIT
