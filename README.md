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

## Configuration

- `config/onemoreclient-common.toml`: currency item, patience, customer count, tip, seat heights, food display.
- `config/onemoreclient/menu.json`: the orderable items. `/onemoreclient reload` reads it again.

```json
[
  {"id": "bread", "item": "minecraft:bread", "price": 2},
  {"id": "soup", "item": "#c:foods/soup", "price": 4, "unlock": {"day": 2}},
  {"id": "pie", "item": {"id": "minecraft:pumpkin_pie", "count": 1}, "price": 6, "match": "exact", "unlock": {"day": 3, "cost": 10}}
]
```

`item` is an item id, a `#tag`, or a full item stack with components. `match` is `ignore` (default) or `exact` (components must be equal).
An entry with `cost` is bought with `/onemoreclient unlock <id>`; the money comes from the register.

## Compatibility

Seats and serving surfaces are block tags: `onemoreclient:seats` and `onemoreclient:serving_surfaces`.
Add blocks of other mods with a datapack. Entries for several furniture mods are included.

## License

MIT
