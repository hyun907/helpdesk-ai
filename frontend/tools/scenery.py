#!/usr/bin/env python3
"""들판 오브젝트(public/scenery/*.svg)를 만드는 생성기.

    python3 tools/scenery.py

── 왜 손으로 찍는가
처음에는 원을 여러 개 합쳐 자동으로 만들었다. 실루엣을 통제할 수 없어서 죄다
둥근 덩어리가 됐다. 픽셀 아트는 칸을 직접 찍어야 형태가 나온다.
아래 SPRITES 의 글자판이 곧 그림이다.

── 색
외곽선은 검정이 아니라 그 재료의 진한 톤이다. 채움색과 같은 계열이어야
그림이 뻣뻣해지지 않는다(화면 UI 의 --px-bd 규칙과 같은 생각).
잔디 위에서 튀지 않도록 전체적으로 옅게 잡았다.

── 배치
글자 위에는 절대 올리지 않는다. 본문 판(.px) 바깥 여백에만 둔다.
화면이 좁아 여백이 사라지면 pixel.css 의 미디어쿼리가 통째로 끈다.
타일이 세로로 반복되므로, 반복이 눈에 띄지 않게 타일을 길게 잡고
나무 밑에 덤불·꽃을 붙이는 식으로 무리지어 놓는다.
"""

# ── 글자판 ────────────────────────────────────────────────────────
#   .  비움      X  외곽선
#   a  밝은 면   b  중간      c  그늘
#   w  나무 밝음 v  나무 중간
#   p  갓·꽃잎   q  갓 그늘   y  꽃술   o  갓 반점
#   s  돌 밝음   t  돌 중간   u  돌 그늘
#   m  줄기      l  잎
SPRITES = {}

SPRITES['tree'] = [
    "...XXX...XXX...",
    "..XaaaXXXaaaX..",
    ".XaaaaaaaaaaaX.",
    "XaaaaaaaaaaaaaX",
    "XaaaaaabbbbbbbX",
    "XaaaabbbbbbbbbX",
    "XaabbbbbbbbbbcX",
    ".XbbbbbbbbbcccX",
    ".XbbbbbbbccccX.",
    "..XbbbbcccccX..",
    "...XXcccccXX...",
    ".....XwwvX.....",
    ".....XwvvX.....",
    ".....XwvvX.....",
    "....XwwvvvX....",
    "....XXXXXXX....",
]

SPRITES['tree2'] = [
    "....XXXXX......",
    "..XXaaaaaXX....",
    ".XaaaaaaaaaX...",
    "XaaaaaaaaaaaX..",
    "XaaaaaabbbbbX..",
    ".XaaabbbbbbbXX.",
    ".XaabbbbbbbbbX.",
    "..XbbbbbbbbcbX.",
    "..XXbbbbbbcccX.",
    "....XXbbbcccX..",
    "......XXcccX...",
    ".....XwwvX.....",
    ".....XwvvX.....",
    "....XwwvvvX....",
    "....XXXXXXX....",
]

SPRITES['bush'] = [
    "...XX...XX...",
    "..XaaXXXaaX..",
    ".XaaaaaaaaaX.",
    "XaaabbbbbbcX.",
    "XaabbbbbbbccX",
    ".XbbbbbbbcccX",
    "..XbbbcccccX.",
    "...XXXXXXX...",
]

SPRITES['mushroom'] = [
    "...XXXXX...",
    ".XXpppppXX.",
    "XppopppqqqX",
    "XpppppoqqqX",
    "XppoppqqqqX",
    ".XqqqqqqqX.",
    "..XXsssXX..",
    "...XsstX...",
    "...XsstX...",
    "...XsstX...",
    "..XssttX...",
    "..XXXXXX...",
]

SPRITES['flower'] = [
    "..XXX..",
    ".XpppX.",
    "XppypqX",
    ".XpqqX.",
    "..XXX..",
    "...m...",
    "..lml..",
    "...m...",
    "...m...",
]

SPRITES['stone'] = [
    "...XXXX....",
    "..XssssXX..",
    ".XssssstttX",
    "XsssstttttX",
    "XssttttttuX",
    ".XtttuuuuX.",
    "..XXXXXXX..",
]

SPRITES['sprout'] = [
    "..X...X..",
    ".XaX.XaX.",
    ".XaaXaaX.",
    "..XamaX..",
    "...XmX...",
    "...XmX...",
    "..XXXXX..",
]

for _n, _rows in SPRITES.items():
    _w = len(_rows[0])
    for _i, _r in enumerate(_rows):
        assert len(_r) == _w, f'{_n} {_i}행 길이 {len(_r)} != {_w}'

# ── 팔레트 ────────────────────────────────────────────────────────
# 잔디(#cfe6a8) 위에서 튀지 않게 옅게 잡았다. 외곽선도 검정이 아니라
# 그 재료의 진한 톤이다.
DAY = {
    'a': '#c3e298', 'b': '#a2ce78', 'c': '#84b25e', 'X': '#5f8a46',
    'w': '#c19a6b', 'v': '#a67c50',
    'p': '#f4c3cc', 'q': '#e2a0ad', 'y': '#f2cf87', 'o': '#fdf3e4',
    's': '#d8d7ca', 't': '#b8b7a8', 'u': '#999888',
    'm': '#7fae5c', 'l': '#9ec97a',
}
# 재료별 외곽선 — 'X' 는 잎 기준이라, 다른 재료는 여기서 갈아 끼운다
OUTLINE_BY_MATERIAL = {
    'w': '#7b5733', 'v': '#7b5733',
    'p': '#c07d8e', 'q': '#c07d8e', 'y': '#c07d8e', 'o': '#c07d8e',
    's': '#7f7e70', 't': '#7f7e70', 'u': '#7f7e70',
}


def night(color, base='#2b3446', keep=0.52):
    """밤에는 바탕 쪽으로 눌러 채도와 밝기를 함께 낮춘다."""
    def hx(h):
        h = h.lstrip('#')
        return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))
    a, b = hx(color), hx(base)
    return '#%02x%02x%02x' % tuple(
        max(0, min(255, round(a[i] * keep + b[i] * (1 - keep)))) for i in range(3))


def resolve(rows):
    """글자판을 (x, y, 색) 목록으로 바꾼다.
       외곽선(X)은 맞닿은 재료에 따라 색을 갈아 끼운다 — 나무 둘레에 초록
       외곽선이 둘러지면 형태가 어긋나 보인다."""
    h, w = len(rows), len(rows[0])
    out = {}
    for y in range(h):
        for x in range(w):
            ch = rows[y][x]
            if ch == '.':
                continue
            if ch != 'X':
                out[(x, y)] = DAY[ch]
                continue
            mats = []
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < w and 0 <= ny < h:
                    c = rows[ny][nx]
                    if c not in '.X':
                        mats.append(c)
            col = DAY['X']
            for m in mats:
                if m in OUTLINE_BY_MATERIAL:
                    col = OUTLINE_BY_MATERIAL[m]
                    break
            out[(x, y)] = col
    return out


# ── 배치 ──────────────────────────────────────────────────────────
# 좌우에 띠 두 개를 세워 두니 '반복되는 기둥'으로 읽혔다. 이제 배경 전체에
# 흩뿌리고, 본문 판이 그 위에 얹힌다 — 판에 가려지는 것은 자연스러운 가림이다.
#
# 위치는 고정 시드로 뽑는다. 손으로 찍으면 규칙이 배어 나오고, 시드를 안 박으면
# 돌릴 때마다 그림이 달라져 diff 가 무의미해진다.
#
# 이음매를 없애려고 가장자리를 넘는 오브젝트는 반대편에도 한 번 더 그린다.
# 그러지 않으려고 오브젝트를 안쪽에만 두면 타일 경계마다 빈 줄이 생겨
# 격자무늬로 보인다.
import random

W, H = 1600, 1500
SEED = 20260821

# (이름, 도트 크기, 뽑을 개수) — 큰 것은 드물게, 작은 것은 흔하게
POPULATION = [
    ('tree',     4, 5), ('tree',     3, 4),
    ('tree2',    4, 4), ('tree2',    3, 4),
    ('bush',     4, 6), ('bush',     3, 7),
    ('stone',    3, 5), ('stone',    2, 5),
    ('mushroom', 3, 5), ('mushroom', 2, 6),
    ('flower',   3, 9), ('flower',   2, 11),
    ('sprout',   3, 8), ('sprout',   2, 10),
]
GAP = 14          # 오브젝트끼리 최소 간격(px)
CLUSTER = 0.45    # 큰 것을 놓은 뒤 곁에 작은 것을 붙일 확률


def _size(name, cell):
    rows = SPRITES[name]
    return len(rows[0]) * cell, len(rows) * cell


def _fits(placed, x, y, w, h):
    for (px, py, pw, ph) in placed:
        # 타일이 반복되므로 겹침도 순환해서 본다
        for ox in (-W, 0, W):
            for oy in (-H, 0, H):
                if (x < px + pw + GAP + ox and x + w + GAP > px + ox and
                        y < py + ph + GAP + oy and y + h + GAP > py + oy):
                    return False
    return True


def scatter():
    rng = random.Random(SEED)
    want = []
    for name, cell, n in POPULATION:
        want += [(name, cell)] * n
    # 큰 것부터 놓아야 자리를 잡는다
    want.sort(key=lambda t: -_size(*t)[0] * _size(*t)[1])

    placed, out = [], []
    for name, cell in want:
        w, h = _size(name, cell)
        for _ in range(400):
            x, y = rng.randrange(0, W), rng.randrange(0, H)
            if _fits(placed, x, y, w, h):
                placed.append((x, y, w, h)); out.append((name, x, y, cell))
                break
        # 큰 것 곁에 작은 것을 붙여 무리를 만든다
        if out and out[-1][0] == name and w >= 40 and rng.random() < CLUSTER:
            comp, ccell = rng.choice([('bush', 2), ('flower', 2), ('sprout', 2), ('mushroom', 2)])
            cw, ch = _size(comp, ccell)
            for _ in range(60):
                cx = x + rng.randint(-w, w)
                cy = y + h - ch + rng.randint(-6, 10)
                cx %= W; cy %= H
                if _fits(placed, cx, cy, cw, ch):
                    placed.append((cx, cy, cw, ch)); out.append((comp, cx, cy, ccell))
                    break
    return out


def merge(rects):
    """가로로 이어붙인 뒤 세로로도 합친다 — 파일이 절반으로 줄어든다."""
    by = {}
    for x, y, w, h, c in rects:
        by.setdefault((x, w, c), []).append((y, h))
    out = []
    for (x, w, c), ys in by.items():
        ys.sort()
        cy, ch = ys[0]
        for y, h in ys[1:]:
            if y == cy + ch:
                ch += h
            else:
                out.append((x, cy, w, ch, c)); cy, ch = y, h
        out.append((x, cy, w, ch, c))
    return out


def build(items, is_night):
    rects = []
    for name, px, py, cell in items:
        rows = SPRITES[name]
        sw, sh = _size(name, cell)
        pixels = resolve(rows)
        # 가장자리를 넘으면 반대편에도 그린다 → 타일 이음매가 사라진다
        offsets = [(0, 0)]
        if px + sw > W: offsets.append((-W, 0))
        if py + sh > H: offsets.append((0, -H))
        if px + sw > W and py + sh > H: offsets.append((-W, -H))
        for ox, oy in offsets:
            for (x, y), col in pixels.items():
                rects.append((px + ox + x * cell, py + oy + y * cell, cell, cell,
                              night(col) if is_night else col))
    # 타일 밖으로 나간 조각은 버린다
    rects = [r for r in rects if r[0] + r[2] > 0 and r[1] + r[3] > 0 and r[0] < W and r[1] < H]
    body = "".join(f"<rect x='{x}' y='{y}' width='{w}' height='{h}' fill='{c}'/>"
                   for x, y, w, h, c in merge(rects))
    return (f"<svg xmlns='http://www.w3.org/2000/svg' width='{W}' height='{H}' "
            f"shape-rendering='crispEdges'>{body}</svg>")


if __name__ == '__main__':
    import pathlib
    items = scatter()
    out = pathlib.Path(__file__).resolve().parent.parent / 'public' / 'scenery'
    out.mkdir(parents=True, exist_ok=True)
    for mode, is_night in (('day', False), ('night', True)):
        f = out / f'field-{mode}.svg'
        f.write_text(build(items, is_night), encoding='utf-8')
        print(f'  {f.name}  {len(f.read_text())//1024}KB')
    print(f'  오브젝트 {len(items)}개 · 타일 {W}x{H}')
