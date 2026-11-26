"""Check money assets and stack arithmetic without Gradle or Minecraft."""
import json
import math
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'src/main/resources/assets/onemorecore'
CLIENT = ROOT / 'src/main/java/com/onemorestudios/onemorecore/client'
source = (CLIENT / 'MoneyRenderer.java').read_text()


def model(path):
    return json.loads((ASSETS / path).read_text())


def values(expression, i=0):
    # The renderer uses Java integer division only in its column and row indices.
    expression = expression.replace('i / 10', 'i // 10').replace('10f', '10.0')
    return tuple(eval(part, {'__builtins__': {}}, {'i': i}) for part in expression.split(','))


def layout(method, count):
    body = source.split(f'static void {method}(')[1].split('\n    }')[0]
    cap = int(re.search(r'Math.min\((\d+), count\)', body)[1])
    moves = re.findall(r'pose.translate\((.*)\);', body)
    scale = values(re.search(r'pose.scale\((.*)\);', body)[1].replace('f', ''))
    angle = re.search(r'rotationDegrees\((.*)\)\);', body)
    return [(values(moves[0], i), scale, values(moves[1], i),
             values(angle[1], i)[0] if angle else 0) for i in range(min(cap, count))]


for kind, piece, bounds, gui in [('coin', 'coin_piece', ([6, 0, 6], [9, 1, 9]), 2.8),
                                  ('banknote', 'banknote', ([4, 0, 6], [12, 1, 10]), 1.6)]:
    block = model(f'models/block/{piece}.json')
    assert len(block['elements']) == 1
    element = block['elements'][0]
    assert (element['from'], element['to']) == bounds
    assert set(block['textures'].values()) == {f'onemorecore:block/{piece}'}
    assert 'particle' in block['textures']
    assert (ASSETS / f'textures/block/{piece}.png').read_bytes().startswith(b'\x89PNG\r\n\x1a\n')
    item = model(f'models/item/{kind}.json')
    assert item['parent'] == f'onemorecore:block/{piece}' and 'elements' not in item
    assert item['display']['gui']['scale'] == [gui] * 3
    for context in ['gui', 'ground', 'fixed', 'firstperson_righthand', 'firstperson_lefthand',
                    'thirdperson_righthand', 'thirdperson_lefthand']:
        assert context in item['display']

for count in [*range(257), 65536, 2147483647]:
    coins, notes = layout('coins', count), layout('banknotes', count)
    assert len(coins) == min(30, count) and len(notes) == min(20, count)
    columns = {}
    for i, (at, scale, pivot, angle) in enumerate(coins):
        assert scale == (1, .5, 1) and pivot == (-7.5 / 16, 0, -7.5 / 16)
        assert at == ((i // 10 - 1) * 3.5 / 16, (i % 10) * .5 / 16, 0)
        columns[at[0]] = columns.get(at[0], 0) + 1
    assert all(n <= 10 for n in columns.values()) and len(columns) <= 3
    for i, (at, scale, pivot, angle) in enumerate(notes):
        assert scale == (1, .25, 1) and pivot == (-.5, 0, -.5)
        assert at == (0, i * .25 / 16, 0) and -8 <= angle <= 8
        # Turned 90 degrees in the drawer: fit its 5.5 by 10 px compartment.
        radians = math.radians(angle)
        width = 4 * abs(math.cos(radians)) + 8 * abs(math.sin(radians))
        depth = 8 * abs(math.cos(radians)) + 4 * abs(math.sin(radians))
        assert width < 5.2 and depth < 8.6
    assert notes == layout('banknotes', count)
assert len({piece[3] for piece in layout('banknotes', 20)}) == 20

scene = model('diegesis/scenes/cash_register.json')
for node in scene['nodes']:
    if node['id'] in ('bills', 'coins'):
        assert 'visual' not in node and 'scale' not in node
        assert node['collider']['to'][1] >= 5
for obsolete in ['block/bill_pile', 'block/coin_pile', 'diegesis/bills', 'diegesis/coins']:
    assert not (ASSETS / f'models/{obsolete}.json').exists()
    for path in [*(CLIENT.glob('*.java')), *(ASSETS.rglob('*.json'))]:
        assert f'onemorecore:{obsolete}' not in path.read_text()
print('Money checks passed: piece assets, item transforms, 259 counts, stack bounds, turns, drawer targets, obsolete references.')
