"""Closed cardboard shipping box, Farmer's Delight / vanilla rules.

usage: python3 make_cardboard_box.py <output folder>

One 12 x 10 x 12 cube on the floor, centred. Everything else is texture, 1 px = 1 unit:
  top     two flaps meeting on a seam along z, packing tape over the seam
  north / south  the tape runs 3 px down from the top edge, "this way up" arrows bottom-left
  east / west    a white shipping label
  bottom  plain, darker
Light from the top-left: top face lightest, a lit top row and left column on each side,
a darker base row so it sits on the floor. Atlas 32 x 32.
"""
import base64, json, os, struct, sys, uuid, zlib

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)

def hx(h): h = h.lstrip('#'); return tuple(int(h[i:i+2], 16) for i in (0, 2, 4)) + (255,)
C = {   # cardboard ramp from the user's reference photo (light kraft): highlights yellower, shadows redder
    'h': hx('#D6B283'), 'c': hx('#C59A69'), 's': hx('#AE8256'), 'd': hx('#8F6745'),
    'k': hx('#6A4A33'),                                   # print ink, flap seam
    't': hx('#E4DACA'), 'T': hx('#F1ECE2'), 'u': hx('#C9BCA6'),   # tape: base, light, shade
    'w': hx('#F2EFE8'), 'g': hx('#8D9196'),               # label paper, label lines
}
N = 32
px = [[(0, 0, 0, 0)] * N for _ in range(N)]
def put(x, y, c): px[y][x] = C[c]
def art(x0, y0, rows):
    for j, r in enumerate(rows):
        for i, ch in enumerate(r): put(x0 + i, y0 + j, ch)

# Top 12 x 12 at (0,0); u = x, v = z (v 0 = north edge). Tape on x 5..6, seam under it.
TOP = []
for z in range(12):
    row = ''
    for x in range(12):
        if x in (5, 6): row += 'T' if x == 5 else 't'
        elif x in (4, 7): row += 'k' if z not in (0, 11) else 's'   # flap edges either side
        elif x == 0 or z == 0: row += 'h'
        elif x == 11 or z == 11: row += 'c'
        else: row += 'h' if (x + z * 3) % 11 else 'c'               # a few fibre specks
    TOP.append(row)
art(0, 0, TOP)
# Bottom 12 x 12 at (12,0)
art(12, 0, ['s' * 12] * 12)
for x in range(12): put(12 + x, 6, 'd')                              # bottom flap seam

def side(rows_fn, x0, y0):
    rows = []
    for y in range(10):
        row = ''
        for x in range(12):
            row += rows_fn(x, y)
        rows.append(row)
    art(x0, y0, rows)
def base(x, y):
    if y == 9: return 'd'
    if y == 0: return 'h'
    if x == 0: return 'h'
    if x == 11: return 's'
    return 'c'
# North / south at (0,12): tape tail down the middle, arrows bottom-left.
ARROW = [".k...k.", "kkk.kkk", ".......", "kkkkkkk"]   # "this way up": two heads over a bar, rows 5..8
ARROWS = {(i, 5 + j): 'k' for j, r in enumerate(ARROW) for i, ch in enumerate(r) if ch == 'k'}
def front(x, y):
    if x in (5, 6) and y <= 2: return 'T' if x == 5 else 't'
    if x in (5, 6) and y == 3: return 'u'
    if (x - 1, y) in ARROWS and x >= 1: return ARROWS[(x - 1, y)]
    return base(x, y)
side(front, 0, 12)
# East / west at (12,12): shipping label 6 x 5.
LABEL = ["wwwwww", "wggggw", "wwwwww", "wgggww", "wwwwww"]   # address lines
def label(x, y):
    if 3 <= x <= 8 and 2 <= y <= 6: return LABEL[y - 2][x - 3]
    if 4 <= x <= 9 and y == 7: return 's'                         # label drop shadow
    if x == 9 and 3 <= y <= 7: return 's'
    return base(x, y)
side(label, 12, 12)

raw = b''.join(b'\x00' + b''.join(bytes(p) for p in row) for row in px)
def chunk(t, d): return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
PNG = (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', N, N, 8, 6, 0, 0, 0))
       + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))
open(f'{OUT}/cardboard_box.png', 'wb').write(PNG)

def F(uv): return {'uv': uv, 'texture': 0}
FRONT, LAB = [0, 12, 12, 22], [12, 12, 24, 22]
els = [{'name': 'box', 'from': [2, 0, 2], 'to': [14, 10, 14], 'type': 'cube', 'box_uv': False,
        'autouv': 0, 'color': 0, 'shade': True, 'export': True, 'origin': [8, 0, 8],
        'rotation': [0, 0, 0], 'uuid': str(uuid.uuid4()), 'faces': {
            'north': F(FRONT), 'south': F(FRONT), 'east': F(LAB), 'west': F(LAB),
            'up': F([0, 0, 12, 12]), 'down': F([12, 0, 24, 12])}}]
gid = str(uuid.uuid4())
json.dump({'meta': {'format_version': '5.0', 'model_format': 'java_block', 'box_uv': False},
           'name': 'cardboard_box', 'parent': 'minecraft:block/block', 'ambientocclusion': True,
           'resolution': {'width': N, 'height': N}, 'elements': els,
           'groups': [{'name': 'cardboard_box', 'origin': [8, 0, 8], 'uuid': gid, 'export': True, 'visibility': True}],
           'outliner': [{'uuid': gid, 'isOpen': True, 'children': [e['uuid'] for e in els]}],
           'textures': [{'name': 'cardboard_box.png', 'relative_path': 'cardboard_box.png', 'folder': 'block',
                         'namespace': '', 'id': '0', 'uuid': str(uuid.uuid4()), 'internal': True,
                         'saved': True, 'width': N, 'height': N, 'uv_width': N, 'uv_height': N,
                         'particle': True, 'render_mode': 'default', 'file_format': 'png',
                         'source': 'data:image/png;base64,' + base64.b64encode(PNG).decode()}]},
          open(f'{OUT}/cardboard_box.bbmodel', 'w'))
print('ok', len(C), 'colours')
