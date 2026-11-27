"""Block models of the open sign: a lone one-block landscape Screen (menu_board), built with the same frame, glass and mount
pieces as screen_models.py so the sign looks exactly like the board. The glass is 14 x 10 px, where OpenSignRenderer draws the
OPEN / CLOSED picture. Run: python3 sign_models.py <assets/onemorecore>."""
import json, os, sys

OUT = sys.argv[1] if len(sys.argv) > 1 else '.'
HERE = os.path.dirname(os.path.abspath(__file__))
# The piece builders of the board (everything before its generation loop).
src = open(os.path.join(HERE, 'screen_models.py')).read()
exec(src[src.index("F, S, P ="):src.index("TEX = {")])

SIGN = {
    'floor': dict(x=(0, 16), y=(2, 15), z=(6, 10), t=1, b=2, gz=(7, 9), mount=[((3, 0, 7), (5, 2, 9)), ((11, 0, 7), (13, 2, 9))]),
    'wall': dict(x=(0, 16), y=(2, 15), z=(2, 6), t=1, b=2, gz=(2, 5), mount=[((6, 7, 0), (10, 11, 2))]),
    'ceiling': dict(x=(0, 16), y=(0, 13), z=(6, 10), t=1, b=2, gz=(7, 9), mount=[((3, 13, 7), (5, 16, 9)), ((11, 13, 7), (13, 16, 9))]),
}
TEX = {'0': 'onemorecore:block/billboard_frame', '1': 'onemorecore:block/billboard_screen', '2': 'onemorecore:block/billboard_post',
       'particle': 'onemorecore:block/billboard_frame'}
# A lone screen: its glass, every edge and corner as frame, and the mount.
LONE = ('glass', 'left_frame', 'right_frame', 'up_frame', 'down_frame', 'ul_frame', 'ur_frame', 'dl_frame', 'dr_frame', 'mount')
os.makedirs(f'{OUT}/models/block/open_sign', exist_ok=True)
for attach, v in SIGN.items():
    p = pieces(v)
    elements = [e for name in LONE for e in p[name]]
    json.dump({'textures': TEX, 'elements': elements}, open(f'{OUT}/models/block/open_sign/screen_{attach}.json', 'w'), indent=1)
    print(attach, len(elements), 'elements')
