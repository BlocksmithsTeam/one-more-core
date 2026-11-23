"""Block models of the Screen (straight states only, turn=0; a screen turned between the four directions is drawn by
ScreenRenderer.Turned with these models) (menu_board) from the textures of the city billboard model (art by the user's friend): the
frame, the glass and the mount for each way it stands (floor: a post or feet; wall: a bracket; ceiling: rods), portrait or
landscape, straight or turned down (wall and ceiling: 22.5 and 45 degrees). Frame edges and corners come in a frame and a
glass version so that joined screens show one glass (multipart on the connect_* properties). The models face south: the
front glass faces +z. Keep in step with ScreenLayout.java."""
import json, os, sys
OUT = sys.argv[1] if len(sys.argv) > 1 else '.'
F, S, P = '#0', '#1', '#2'  # frame, screen (glass), post textures

# Variant geometry: panel x0..x1, y0..y1, z0..z1; frame widths (left/right 1; top t; bottom b); glass core z (front face at gz1;
# back face at gz0 for two-sided); pivot (y, z) for turning down; mount boxes.
V = {
    ('wall', 'p'): dict(x=(0, 16), y=(0, 32), z=(2, 6), t=2, b=2, gz=(2, 5), two=False, pivot=(0, 2), mount=[((6, 14, 0), (10, 18, 2))]),
    ('wall', 'l'): dict(x=(0, 32), y=(0, 16), z=(2, 6), t=1, b=2, gz=(2, 5), two=False, pivot=(0, 2), mount=[((14, 6, 0), (18, 10, 2))]),
    ('floor', 'p'): dict(x=(0, 16), y=(6, 32), z=(6, 10), t=2, b=2, gz=(7, 9), two=True, pivot=None, mount=[((5, 0, 7), (11, 6, 9))]),
    ('floor', 'l'): dict(x=(0, 32), y=(2, 16), z=(6, 10), t=1, b=2, gz=(7, 9), two=True, pivot=None,
                         mount=[((3, 0, 7), (5, 2, 9)), ((27, 0, 7), (29, 2, 9))]),
    ('ceiling', 'p'): dict(x=(0, 16), y=(-16, 12), z=(6, 10), t=2, b=2, gz=(7, 9), two=True, pivot=(12, 8),
                           mount=[((3, 12, 7), (5, 16, 9)), ((11, 12, 7), (13, 16, 9))]),
    ('ceiling', 'l'): dict(x=(0, 32), y=(0, 13), z=(6, 10), t=1, b=2, gz=(7, 9), two=True, pivot=(13, 8),
                           mount=[((3, 13, 7), (5, 16, 9)), ((27, 13, 7), (29, 16, 9))]),
}


def uvs(tex, w, h, kind):
    """Face UVs (16-space of a 32 px texture: 1 model px = 1 texel = 0.5 uv)."""
    def clip(u0, v0, du, dv, umax, vmax):
        return [u0, v0, u0 + min(du, umax), v0 + min(dv, vmax)]
    if tex == F:
        if kind == 'h': return clip(0, 0, w / 2, h / 2, 8, 1)
        if kind == 'v': return clip(7.5, 1, w / 2, h / 2, 0.5, 11)
        return clip(8, 14, w / 2, h / 2, 8, 2)  # depth faces: the dark strip
    if tex == S:
        if kind == 'core': return clip(0, 0, w / 2, h / 2, 7, 11)
        if kind == 'fill': return clip(5, 8, w / 2, h / 2, 2, 3)
        return [8, 0, 8.5, 11]  # sides of the glass
    return clip(0, 0, w / 2, h / 2, 3, 3)


def box(frm, to, tex, kind, name, hide=()):
    w, h, d = to[0] - frm[0], to[1] - frm[1], to[2] - frm[2]
    faces = {}
    for f, (fw, fh) in {'north': (w, h), 'south': (w, h), 'east': (d, h), 'west': (d, h), 'up': (w, d), 'down': (w, d)}.items():
        if f in hide: continue
        k = kind if f in ('north', 'south') else ('side' if tex != P else kind)
        if tex == F and f in ('up', 'down') and kind == 'h': k = 'side'
        faces[f] = {'uv': uvs(tex, fw, fh, k), 'texture': tex}
    return {'name': name, 'from': list(frm), 'to': list(to), 'faces': faces}


def split(x0, x1, step=16):
    out, x = [], x0
    while x < x1:
        out.append((x, min(x1, x + step)))
        x = min(x1, x + step)
    return out


def pieces(v):
    (x0, x1), (y0, y1), (z0, z1), t, b = v['x'], v['y'], v['z'], v['t'], v['b']
    gz0, gz1 = v['gz']
    p = {}
    # The glass core, between the frames, split in pieces of at most 15 px so that its texture does not stretch.
    p['glass'] = [box((a, y0 + b, gz0), (c, y1 - t, gz1), S, 'core', 'glass') for a, c in split(x0 + 1, x1 - 1, 15)]
    # Edges: frame or glass.
    p['left_frame'] = [box((x0, y0 + b, z0), (x0 + 1, y1 - t, z1), F, 'v', 'left')]
    p['left_glass'] = [box((x0, y0 + b, gz0), (x0 + 1, y1 - t, gz1), S, 'fill', 'left')]
    p['right_frame'] = [box((x1 - 1, y0 + b, z0), (x1, y1 - t, z1), F, 'v', 'right')]
    p['right_glass'] = [box((x1 - 1, y0 + b, gz0), (x1, y1 - t, gz1), S, 'fill', 'right')]
    p['up_frame'] = [box((a, y1 - t, z0), (c, y1, z1), F, 'h', 'top') for a, c in split(x0 + 1, x1 - 1)]
    p['up_glass'] = [box((a, y1 - t, gz0), (c, y1, gz1), S, 'fill', 'top') for a, c in split(x0 + 1, x1 - 1)]
    p['down_frame'] = [box((a, y0, z0), (c, y0 + b, z1), F, 'h', 'bottom') for a, c in split(x0 + 1, x1 - 1)]
    p['down_glass'] = [box((a, y0, gz0), (c, y0 + b, gz1), S, 'fill', 'bottom') for a, c in split(x0 + 1, x1 - 1)]
    for c, (xa, xb), (ya, yb) in (('ul', (x0, x0 + 1), (y1 - t, y1)), ('ur', (x1 - 1, x1), (y1 - t, y1)),
                                   ('dl', (x0, x0 + 1), (y0, y0 + b)), ('dr', (x1 - 1, x1), (y0, y0 + b))):
        p[c + '_frame'] = [box((xa, ya, z0), (xb, yb, z1), F, 'h', 'corner')]
        p[c + '_glass'] = [box((xa, ya, gz0), (xb, yb, gz1), S, 'fill', 'corner')]
    p['mount'] = [box(f, t2, P, 'post', 'mount') for f, t2 in v['mount']]
    return p


TEX = {'0': 'onemorecore:block/billboard_frame', '1': 'onemorecore:block/billboard_screen', '2': 'onemorecore:block/billboard_post',
       'particle': 'onemorecore:block/billboard_frame'}
FACINGS = (('south', 0), ('west', 90), ('north', 180), ('east', 270))
os.makedirs(f'{OUT}/models/block/screen', exist_ok=True)
multipart = []
for (attach, orient), v in V.items():
    tilts = (0,) if v['pivot'] is None else (0, 1, 2)
    for tilt in tilts:
        for name, els in pieces(v).items():
            els = json.loads(json.dumps(els))
            if tilt and name != 'mount':
                for e in els: e['rotation'] = {'angle': tilt * 22.5, 'axis': 'x', 'origin': [8, v['pivot'][0], v['pivot'][1]]}
            if name == 'mount' and attach == 'wall' and tilt:
                # Turned down, the panel's back leaves the wall: the bracket becomes an L, a plate on the wall and an arm out to
                # the panel's back at the bracket's height (the panel turns about its bottom back edge, pivot (y 0, z 2)).
                import math
                (bx0, by0, _), (bx1, by1, _) = v['mount'][0]
                yb = (by0 + by1) / 2
                a = math.radians(tilt * 22.5)
                y1 = round(v['pivot'][0] + (yb - v['pivot'][0]) * math.cos(a))
                z1 = max(2, round(v['pivot'][1] + (yb - v['pivot'][0]) * math.sin(a)))
                cx = (bx0 + bx1) / 2
                els = [box((bx0, y1 - 2, 0), (bx1, y1 + 2, 1), P, 'post', 'mount_plate'),
                       box((cx - 1, y1 - 1, 1), (cx + 1, y1 + 1, z1), P, 'post', 'mount_arm')]
            model = f'screen/{attach}_{orient}_{tilt}_{name}'
            json.dump({'textures': TEX, 'elements': els}, open(f'{OUT}/models/block/{model}.json', 'w'), indent=1)
            base = {'face': attach, 'landscape': 'true' if orient == 'l' else 'false', 'tilt': str(tilt), 'turn': '0'}
            side = name.split('_')[0]
            conds = []
            if name.endswith('_frame') and side in ('left', 'right', 'up', 'down'):
                conds = [{**base, f'connect_{side}': 'false'}]
            elif name.endswith('_glass') and side in ('left', 'right', 'up', 'down'):
                conds = [{**base, f'connect_{side}': 'true'}]
            elif name.endswith('_frame'):  # a corner: frame unless both of its edges are joined
                a, b2 = {'u': 'up', 'd': 'down'}[side[0]], {'l': 'left', 'r': 'right'}[side[1]]
                conds = [{**base, f'connect_{a}': 'false'}, {**base, f'connect_{b2}': 'false'}]
            elif name.endswith('_glass'):
                a, b2 = {'u': 'up', 'd': 'down'}[side[0]], {'l': 'left', 'r': 'right'}[side[1]]
                conds = [{**base, f'connect_{a}': 'true', f'connect_{b2}': 'true'}]
            else:
                conds = [base]
            for facing, y in FACINGS:
                when = [{**c, 'facing': facing} for c in conds]
                multipart.append({'when': when[0] if len(when) == 1 else {'OR': when},
                                  'apply': {'model': f'onemorecore:block/{model}', **({'y': y} if y else {})}})
json.dump({'multipart': multipart}, open(f'{OUT}/blockstates/menu_board.json', 'w'))
print(len(multipart), 'parts')
