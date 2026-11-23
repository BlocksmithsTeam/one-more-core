#!/usr/bin/env python3
"""The app icons of the Restaurant Computer's desktop, as 32 x 32 pixel art.

Each icon is a rounded tile in one solid colour with a 1-px darker border (2 px at the bottom) and a white glyph with a
1-px drop shadow in the border colour: hard pixels, four colours per icon. The glyphs are the hand-written maps below
('#' is white, '.' shows the tile); edit a map and run this script again:

    python3 art/computer/make_icons.py

It writes src/main/resources/assets/onemorecore/textures/gui/sprites/computer/app_<name>.png. Pure Python (zlib and
struct), no PIL.
"""
import os
import struct
import zlib

SIZE = 32
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "src", "main", "resources", "assets",
                   "onemorecore", "textures", "gui", "sprites", "computer")

# Menu: a cloche (serving dome) on a plate.
CLOCHE = [
    "..........##..........",
    ".........####.........",
    "..........##..........",
    ".......########.......",
    ".....############.....",
    "....####..########....",
    "...###..###########...",
    "..###.##############..",
    "..##.###############..",
    ".##.#################.",
    ".####################.",
    ".####################.",
    "......................",
    "######################",
    "######################",
    "..##################..",
]

# Shop: a shopping cart.
CART = [
    "###...................",
    ".###..................",
    "..####################",
    "...##..##..##..##..##.",
    "...##..##..##..##..##.",
    "...##################.",
    "....#..##..##..##..#..",
    "....#..##..##..##..#..",
    "....################..",
    ".....##...............",
    ".....################.",
    "......................",
    "......##........##....",
    ".....####......####...",
    ".....####......####...",
    "......##........##....",
]

# Upgrades: an up arrow over two bars.
UPGRADES = [
    "..........##..........",
    ".........####.........",
    "........######........",
    ".......########.......",
    "......##########......",
    ".....############.....",
    "....##############....",
    "...################...",
    "........######........",
    "........######........",
    "........######........",
    "......................",
    "...################...",
    "...################...",
    "......................",
    "...################...",
    "...################...",
]

# Manager: a shop front with an awning, a window and a door.
SHOP_FRONT = [
    ".####################.",
    "######################",
    "##..##..##..##..##..##",
    "##..##..##..##..##..##",
    ".##..##..##..##..##..#",
    "..##################..",
    "..##..............##..",
    "..##.#######.####.##..",
    "..##.#..#..#.#..#.##..",
    "..##.#..#..#.#..#.##..",
    "..##.#######.#..#.##..",
    "..##.#..#..#.#.##.##..",
    "..##.#######.#..#.##..",
    "..##.........#..#.##..",
    "######################",
    "######################",
]

# Stream: a broadcast mast sending signal waves.
BROADCAST = [
    "...##............##...",
    "..##..............##..",
    ".##....##....##....##.",
    ".##...##......##...##.",
    "##...##...##...##...##",
    "##...##..####..##...##",
    "##...##..####..##...##",
    "##...##...##...##...##",
    ".##...##..##..##...##.",
    ".##....#..##..#....##.",
    "..##......##......##..",
    "...##....####....##...",
    ".........####.........",
    "........##..##........",
    "........##..##........",
    ".......##....##.......",
]

# name: (tile colour, glyph)
ICONS = {
    "menu": (0xD85A30, CLOCHE),
    "shop": (0x1D9E75, CART),
    "upgrades": (0xBA7517, UPGRADES),
    "manager": (0x5F5E5A, SHOP_FRONT),
    "stream": (0x7F77DD, BROADCAST),
}

# Pixels cut from each end of the tile's first rows (the rounded corners); the bottom rows mirror them.
CORNER = [3, 1, 1]
WHITE = (255, 255, 255, 255)


def darker(rgb, f=0.66):
    return tuple(int(((rgb >> s) & 0xFF) * f) for s in (16, 8, 0)) + (255,)


def solid(rgb):
    return ((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 255)


def in_tile(x, y):
    if not (0 <= x < SIZE and 0 <= y < SIZE):
        return False
    row = min(y, SIZE - 1 - y)
    cut = CORNER[row] if row < len(CORNER) else 0
    return cut <= x < SIZE - cut


def icon(color, glyph):
    width = len(glyph[0])
    assert all(len(r) == width for r in glyph), "every row of a glyph must have the same width"
    fill, edge = solid(color), darker(color)
    px = [[(0, 0, 0, 0)] * SIZE for _ in range(SIZE)]
    for y in range(SIZE):
        for x in range(SIZE):
            if not in_tile(x, y):
                continue
            border = any(not in_tile(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))) or not in_tile(x, y + 2)
            px[y][x] = edge if border else fill
    # The glyph centred a pixel above the middle, so that its shadow and the thicker bottom border balance it.
    ox, oy = (SIZE - width) // 2, (SIZE - len(glyph)) // 2 - 1
    for y, row in enumerate(glyph):
        for x, c in enumerate(row):
            if c == "#" and px[oy + y + 1][ox + x] == fill:
                px[oy + y + 1][ox + x] = edge
    for y, row in enumerate(glyph):
        for x, c in enumerate(row):
            if c == "#":
                px[oy + y][ox + x] = WHITE
    return px


def png(path, px):
    raw = b"".join(b"\x00" + b"".join(struct.pack("4B", *p) for p in row) for row in px)

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


if __name__ == "__main__":
    for name, (color, glyph) in ICONS.items():
        path = os.path.normpath(os.path.join(OUT, "app_" + name + ".png"))
        png(path, icon(color, glyph))
        print(path)
