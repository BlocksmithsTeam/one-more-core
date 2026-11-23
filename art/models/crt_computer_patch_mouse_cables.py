"""Patch crt_computer.bbmodel: a better mouse and thinner cables.

usage: python3 patch_mouse_cables.py <in.bbmodel> <out.bbmodel> <out_texture.png>

Mouse  2 x 1 x 3 (was 2 x 1 x 2): palm, a seam and the buttons in the texture; the cable leaves from the button end.
Cables 0.5 x 0.5 (was 1 x 1), same routes: keyboard -> monitor, mouse -> monitor, power drop.
       Lit top faces, darker sides, from two flat colour blocks in the free texture space.
"""
import base64, json, sys, uuid
sys.path.insert(0, __import__('os').path.dirname(__file__) or '.')

SRC, DST, TEX_OUT = sys.argv[1:4]
m = json.load(open(SRC))

# --- texture -------------------------------------------------------------------------------------
import struct, zlib
def decode(b):
    w, h = struct.unpack('>II', b[16:24]); ct = b[25]; bpp = 4 if ct == 6 else 3
    data = b''; i = 8
    while i < len(b):
        n = struct.unpack('>I', b[i:i+4])[0]
        if b[i+4:i+8] == b'IDAT': data += b[i+8:i+8+n]
        i += 12 + n
    raw = zlib.decompress(data); st = w * bpp; out = []; prev = bytearray(st); p = 0
    for y in range(h):
        f = raw[p]; line = bytearray(raw[p+1:p+1+st]); p += 1 + st
        for x in range(st):
            a = line[x-bpp] if x >= bpp else 0; u = prev[x]; c = prev[x-bpp] if x >= bpp else 0
            if f == 1: line[x] = (line[x] + a) & 255
            elif f == 2: line[x] = (line[x] + u) & 255
            elif f == 3: line[x] = (line[x] + (a + u) // 2) & 255
            elif f == 4:
                pa, pb, pc = abs(u - c), abs(a - c), abs(a + u - 2 * c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else (u if pb <= pc else c))) & 255
        prev = line
        out.append([list(line[x*bpp:x*bpp+3]) + [line[x*bpp+3] if bpp == 4 else 255] for x in range(w)])
    return out
def encode(px):
    w, h = len(px[0]), len(px)
    raw = b''.join(b'\x00' + b''.join(bytes(p) for p in row) for row in px)
    def ch(t, d): return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    return (b'\x89PNG\r\n\x1a\n' + ch(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
            + ch(b'IDAT', zlib.compress(raw, 9)) + ch(b'IEND', b''))

tex = m['textures'][0]
px = decode(base64.b64decode(tex['source'].split(',')[1]))
# beige plastic ramp from the existing keyboard/monitor, plus a seam and the wheel
COL = {'h': (233, 221, 190), 'b': (217, 210, 184), 's': (194, 183, 152), 'd': (138, 128, 104),
       'w': (65, 69, 70), 'K': (46, 46, 48), 'k': (65, 69, 70)}
def put(x, y, c): px[y][x] = list(COL[c]) + [255]
def art(x0, y0, rows):
    for j, r in enumerate(rows):
        for i, c in enumerate(r): put(x0 + i, y0 + j, c)
# all in the empty band y 52..63
MOUSE_UP = ["bh",       # palm (user side, -z); +x is the viewer's left = lit
            "dd",       # seam between palm and buttons
            "bh"]       # buttons
art(0, 52, MOUSE_UP)                    # up 2x3
art(3, 52, ["bh"])                      # north: palm end
art(3, 53, ["dd"])                      # south: button end, cable leaves here
art(6, 52, ["sbb"]); art(6, 53, ["sss"])       # east / west 3x1 (lit / shaded)
art(11, 52, ["dd"] * 3)                 # down 2x3
for y in range(52, 64):                 # cable colour blocks
    for x in range(24, 36): put(x, y, 'k')     # top faces
    for x in range(36, 48): put(x, y, 'K')     # sides and ends
png = encode(px)
open(TEX_OUT, 'wb').write(png)
tex['source'] = 'data:image/png;base64,' + base64.b64encode(png).decode()

# --- elements ------------------------------------------------------------------------------------
def F(uv): return {'uv': uv, 'texture': 0}
def cable(name, f, t):
    dx, dy, dz = (t[i] - f[i] for i in range(3))
    top = lambda w, h: F([24, 52, 24 + w, 52 + h])
    side = lambda w, h: F([36, 52, 36 + w, 52 + h])
    return {'name': name, 'from': f, 'to': t, 'type': 'cube', 'box_uv': False, 'autouv': 0,
            'color': 0, 'shade': True, 'export': True, 'origin': f, 'rotation': [0, 0, 0],
            'uuid': str(uuid.uuid4()), 'faces': {
                'north': side(dx, dy), 'south': side(dx, dy), 'east': side(dz, dy), 'west': side(dz, dy),
                'up': top(dx, dz), 'down': side(dx, dz)}}
MOUSE = {'name': 'mouse', 'from': [13, 0, 2], 'to': [15, 1, 5], 'type': 'cube', 'box_uv': False,
         'autouv': 0, 'color': 0, 'shade': True, 'export': True, 'origin': [13, 0, 2],
         'rotation': [0, 0, 0], 'uuid': str(uuid.uuid4()), 'faces': {
             'up': F([0, 52, 2, 55]), 'down': F([11, 52, 13, 55]),
             'north': F([3, 52, 5, 53]), 'south': F([3, 53, 5, 54]),
             'east': F([6, 52, 9, 53]), 'west': F([6, 53, 9, 54])}}
NEW = {
    'mouse_right': [MOUSE],
    'keyboard_cable_lead':   [cable('keyboard_cable_lead',   [7.25, 0, 5],     [7.75, 0.5, 6.75])],
    'keyboard_cable_turn':   [cable('keyboard_cable_turn',   [7.75, 0, 6.25],  [9.25, 0.5, 6.75])],
    'keyboard_cable_return': [cable('keyboard_cable_return', [9.25, 0, 6.25],  [9.75, 0.5, 8])],
    'mouse_cable_lead_right':   [cable('mouse_cable_lead',   [13.75, 0, 5],  [14.25, 0.5, 11.75])],
    'mouse_cable_return_right': [cable('mouse_cable_return', [11, 0, 11.25],   [13.75, 0.5, 11.75])],
    'power_cable_drop': [cable('power_cable_drop', [8.25, 0.5, 15], [8.75, 3, 15.5])],
    'power_cable_tail': [cable('power_cable_tail', [8.25, 0, 15],   [13, 0.5, 15.5])],
}
old_uuid = {}
els = []
for e in m['elements']:
    if e['name'] in NEW:
        for n in NEW[e['name']]:
            old_uuid.setdefault(e['uuid'], []).append(n['uuid']); els.append(n)
    else:
        els.append(e)
assert len(old_uuid) == len(NEW), 'element names changed'
m['elements'] = els
def fix(node):
    if isinstance(node, list):
        out = []
        for c in node:
            if isinstance(c, str) and c in old_uuid: out += old_uuid[c]
            else: out.append(fix(c))
        return out
    if isinstance(node, dict) and 'children' in node: node['children'] = fix(node['children'])
    return node
m['outliner'] = fix(m['outliner'])
json.dump(m, open(DST, 'w'))
print('ok', len(els), 'elements')
