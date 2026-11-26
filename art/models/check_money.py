"""Check money assets and renderer transforms without Gradle or Minecraft."""
import itertools
import json
import math
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'src/main/resources/assets/onemorecore'
CLIENT = ROOT / 'src/main/java/com/onemorestudios/onemorecore/client'
source = (CLIENT / 'MoneyRenderer.java').read_text()
scene = json.loads((ASSETS / 'diegesis/scenes/cash_register.json').read_text())
nodes = {node['id']: node for node in scene['nodes']}
spots = [tuple(map(float, row.split(','))) for row in re.findall(
    r'\{([^{}]+)\}', source.split('DRAWER_COINS = {')[1].split('};')[0])]


def model(path):
    return json.loads((ASSETS / path).read_text())


def values(expression, i=0):
    # Only column/row indices use Java integer division; all remaining divisions are doubles.
    expression = re.sub(r'i / (5|15)\b', r'i // \1', expression).replace('10f', '10.0')
    return tuple(eval(part.strip(), {'__builtins__': {}}, {'i': i, 'at': spots[i % 6]})
                 for part in expression.split(','))


def layout(method, count):
    body = source.split(f'static void {method}(')[1].split('\n    }')[0]
    cap = int(re.search(r'Math.min\((\d+), count\)', body)[1])
    moves = re.findall(r'pose.translate\((.*?)\);', body, re.S)
    assert len(moves) == 2 and 'pose.scale' not in body
    angle = re.search(r'rotationDegrees\((.*)\)\);', body)
    return [(values(moves[0], i), values(moves[1], i),
             values(angle[1], i)[0] if angle else 0) for i in range(min(cap, count))]


def vertices(piece, bounds, origin):
    at, pivot, angle = piece
    c, s = math.cos(math.radians(angle)), math.sin(math.radians(angle))
    for point in itertools.product(*zip(*bounds)):
        x, y, z = (point[k] + pivot[k] * 16 for k in range(3))
        yield (origin[0] + at[0] * 16 + c * x + s * z,
               origin[1] + at[1] * 16 + y,
               origin[2] + at[2] * 16 - s * x + c * z)


bounds = {'coins': ([6, 0, 6], [9, 1, 9]), 'banknotes': ([2, 0, 5], [14, 1, 11])}
for kind, piece, method, gui in [('coin', 'coin_piece', 'coins', 2.8),
                                ('banknote', 'banknote', 'banknotes', 1.066667)]:
    block = model(f'models/block/{piece}.json')
    assert len(block['elements']) == 1
    assert (block['elements'][0]['from'], block['elements'][0]['to']) == bounds[method]
    assert set(block['textures'].values()) == {f'onemorecore:block/{piece}'}
    assert 'particle' in block['textures']
    assert (ASSETS / f'textures/block/{piece}.png').read_bytes().startswith(b'\x89PNG\r\n\x1a\n')
    item = model(f'models/item/{kind}.json')
    assert item['parent'] == f'onemorecore:block/{piece}' and 'elements' not in item
    assert item['display']['gui']['scale'] == [gui] * 3
    assert item['display']['gui']['rotation'] == [80, 0, 0]
    for context in ['gui', 'ground', 'fixed', 'firstperson_righthand', 'firstperson_lefthand',
                    'thirdperson_righthand', 'thirdperson_lefthand']:
        assert context in item['display']

# Read the actual table origins, so a caller translation cannot invalidate the fit check silently.
table = (CLIENT / 'DirtRenderer.java').read_text().split('pose.translate(pos.getX()')[1]
moves = re.findall(r'pose.translate\((.*?)\);', table, re.S)
note_origin = tuple(v * 16 for v in values(moves[0]))
coin_origin = tuple(a + v * 16 for a, v in zip(note_origin, values(moves[1])))
for count in [-1, *range(257), 65536, 2147483647]:
    coins, notes = layout('coins', count), layout('banknotes', count)
    assert len(coins) == max(0, min(30, count)) and len(notes) == max(0, min(20, count))
    columns = {}
    for i, (at, pivot, angle) in enumerate(coins):
        column = (i // 5 % 3, i // 15)
        columns[column] = columns.get(column, 0) + 1
        assert at[1] * 16 == i % 5 and -10 <= angle <= 10
        assert abs(at[0] * 16 - (column[0] - 1) * 4.5) <= .5
        assert abs(at[2] * 16 - (column[1] - .5) * 4.5) <= .5
    assert all(n <= 5 for n in columns.values()) and len(columns) <= 6
    if count == 7:
        assert list(columns.values()) == [5, 2]
    for i, (at, pivot, angle) in enumerate(notes):
        assert at == (0, i / 16, 0) and -8 <= angle <= 8
    for method, pieces, origin in [('coins', coins, coin_origin), ('banknotes', notes, note_origin)]:
        for piece in pieces:
            for x, y, z in vertices(piece, bounds[method], origin):
                assert 0 <= x <= 16 and 0 <= z <= 16, (method, count, x, z)
                assert y >= 0
    for method, cap, node in [('drawerCoins', 6, 'coins'), ('drawerBanknotes', 2, 'bills')]:
        pieces = layout(method, count)
        assert len(pieces) == max(0, min(cap, count))
        for i, piece in enumerate(pieces):
            assert piece[2] == 0
            kind = 'coins' if node == 'coins' else 'banknotes'
            points = list(vertices(piece, bounds[kind], nodes[node]['pos']))
            low = tuple(min(p[k] for p in points) for k in range(3))
            high = tuple(max(p[k] for p in points) for k in range(3))
            expected = spots[i] if node == 'coins' else (2, .5 + i, -8.5)
            size = (3, 1, 3) if node == 'coins' else (12, 1, 6)
            assert all(math.isclose(low[k], expected[k], abs_tol=1e-12) for k in range(3))
            assert all(math.isclose(high[k] - low[k], size[k]) for k in range(3))
            assert low[0] >= 2 and high[0] <= 14
            assert low[2] >= (-2 if node == 'coins' else -8.5)
            assert high[2] <= (2 if node == 'coins' else -2.5)
    assert coins == layout('coins', count) and notes == layout('banknotes', count)
assert len(layout('drawerCoins', 12)) == 6
assert spots == [(2.5, .5, -1.5), (6.2, .5, -1.5), (10.3, .5, -1.5),
                 (6.7, 1.5, -1.5), (10.8, 1.5, -1.5), (10.3, 2.5, -1)]
assert len({piece[2] for piece in layout('banknotes', 20)}) == 20
assert len({piece[2] for piece in layout('coins', 30)}) > 1

behavior = (CLIENT / 'RegisterBehavior.java').read_text()
assert 'rotationDegrees' not in behavior and behavior.count('pose.scale(16, 16, 16)') == 2
assert 'MoneyRenderer.drawerBanknotes(' in behavior and 'MoneyRenderer.drawerCoins(' in behavior
for name, center, extent in [('bills', [8, .5, -5.5], [6, 5, 3]), ('coins', [8, .5, 0], [6, 5, 2])]:
    node = nodes[name]
    assert node['pos'] == center
    assert node['collider'] == {'from': [-extent[0], 0, -extent[2]], 'to': extent}
    assert 'visual' not in node and 'scale' not in node
for name in ['block/cash_register', 'diegesis/register_body', 'diegesis/register_drawer']:
    asset = model(f'models/{name}.json')
    assert asset['texture_size'] == [32, 32]
    assert asset['textures'] == {'0': 'onemorecore:block/cash_register', 'particle': 'onemorecore:block/cash_register'}
# Project every drawer corner into the camera frustum at 4:3 (also fits wider viewports).
camera = scene['camera']
eye, target = camera['position'], camera['target']
forward = [b - a for a, b in zip(eye, target)]
length = math.sqrt(sum(v * v for v in forward))
forward = [v / length for v in forward]
assert eye[0] == target[0]  # This scene's camera has no yaw.
up = [0, forward[2], -forward[1]]
vertical = math.tan(math.radians(camera['fov'] / 2))
for element in model('models/diegesis/register_drawer.json')['elements']:
    for point in itertools.product(*zip(element['from'], element['to'])):
        delta = [p - e for p, e in zip(point, eye)]
        depth = sum(a * b for a, b in zip(delta, forward))
        height = sum(a * b for a, b in zip(delta, up))
        assert depth > 0 and abs(height) < depth * vertical
        assert abs(delta[0]) < depth * vertical * 4 / 3

shape = (CLIENT.parent / 'CashRegisterBlock.java').read_text().split('SHAPES = ')[1].split(';')[0]
boxes = [values(box) for box in re.findall(r'new double\[\] \{([^}]+)\}', shape)]
assert boxes == [(1, 0, 2, 15, 3, 13), (2, 3, 3, 14, 7, 13)]
for obsolete in ['block/bill_pile', 'block/coin_pile', 'diegesis/bills', 'diegesis/coins']:
    assert not (ASSETS / f'models/{obsolete}.json').exists()
    for path in [*(CLIENT.glob('*.java')), *(ASSETS.rglob('*.json'))]:
        assert f'onemorecore:{obsolete}' not in path.read_text()
print('Money checks passed: assets, 260 counts, five-coin columns, table bounds, turns, exact drawer positions and caps, targets.')
