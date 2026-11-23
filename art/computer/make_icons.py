#!/usr/bin/env python3
"""The app icons of the Restaurant Computer's desktop, as 32 x 32 pixel art.

Each icon is a flat rounded tile in the app's one colour with a bold white glyph: hard pixels, two colours per icon. The
glyphs are the hand-written maps below ('#' is white, '.' shows the tile); edit a map and run this script again:

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

# Menu: a cloche dome on a plate.
CLOCHE = [
    ".........##.........",
    "........####........",
    "....############....",
    "...##############...",
    "..################..",
    ".##################.",
    ".##################.",
    ".##################.",
    "....................",
    "####################",
    "####################",
]

# Shop: a cart outline with two wheels.
CART = [
    "####................",
    "####................",
    "..##################",
    "..##################",
    "...##............##.",
    "...##............##.",
    "....##..........##..",
    "....##..........##..",
    ".....############...",
    ".....############...",
    "....................",
    "......##......##....",
    ".....####....####...",
    ".....####....####...",
    "......##......##....",
]

# Upgrades: one up arrow.
UPGRADES = [
    "........##........",
    ".......####.......",
    "......######......",
    ".....########.....",
    "....##########....",
    "...############...",
    "..##############..",
    ".################.",
    "......######......",
    "......######......",
    "......######......",
    "......######......",
    "......######......",
    "......######......",
    "......######......",
    "......######......",
]

# Manager: a shop front with an awning and a door.
SHOP_FRONT = [
    ".##################.",
    "####################",
    "####################",
    "####################",
    ".##..##..##..##..##.",
    "....................",
    "..################..",
    "..################..",
    "..######....######..",
    "..######....######..",
    "..######....######..",
    "..######....######..",
    "..######....######..",
    "..######....######..",
]

# Stream: a dot with two arcs on each side.
BROADCAST = [
    "..##..............##..",
    ".##....##....##....##.",
    "##....##......##....##",
    "##...##...##...##...##",
    "##...##..####..##...##",
    "##...##..####..##...##",
    "##...##...##...##...##",
    "##....##......##....##",
    ".##....##....##....##.",
    "..##..............##..",
]

# name: (tile colour, glyph)
ICONS = {
    "menu": (0xD85A30, CLOCHE),
    "shop": (0x1D9E75, CART),
    "upgrades": (0xBA7517, UPGRADES),
    "manager": (0x5F5E5A, SHOP_FRONT),
    "stream": (0x7F77DD, BROADCAST),
}

# The radius of the tile's rounded corners; ComputerScreen cuts its hover outline and dim overlay the same way.
RADIUS = 7
WHITE = (255, 255, 255, 255)


def solid(rgb):
    return ((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 255)


def corner_cuts(r):
    """Pixels cut from each end of the first r rows: a pixel stays if its centre is inside the corner's circle."""
    cuts = []
    for i in range(r):
        dy = r - i - 0.5
        j = 0
        while (r - j - 0.5) ** 2 + dy * dy > r * r:
            j += 1
        cuts.append(j)
    return cuts


CUTS = corner_cuts(RADIUS)


def in_tile(x, y):
    row = min(y, SIZE - 1 - y)
    cut = CUTS[row] if row < len(CUTS) else 0
    return cut <= x < SIZE - cut


def icon(color, glyph):
    width = len(glyph[0])
    assert all(len(r) == width for r in glyph), "every row of a glyph must have the same width"
    px = [[solid(color) if in_tile(x, y) else (0, 0, 0, 0) for x in range(SIZE)] for y in range(SIZE)]
    ox, oy = (SIZE - width) // 2, (SIZE - len(glyph)) // 2
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
