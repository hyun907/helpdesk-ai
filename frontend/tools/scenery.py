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

SPRITES['log'] = [
    "..XXXXXXXXX..",
    ".XwwvvvvvvvX.",
    "XwXvXvvvvvvvX",
    "XwXvXvvvvvvvX",
    ".XwwvvvvvvvX.",
    "..XXXXXXXXX..",
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
# (이름, 왼쪽 px, 위 px, 도트 크기)
# 무리지어 둔다 — 나무 밑에 덤불, 그 옆에 꽃. 규칙적으로 흩뿌리면
# '반복되는 무늬'로 읽히고 자연스럽지 않다.
# 타일이 세로로 반복되므로 위아래 경계를 넘으면 안 된다.
W, H = 176, 2600

LEFT = [
    ('tree',    18,  40, 4), ('bush',    72, 132, 3), ('flower',   4, 150, 3),
    ('sprout', 108, 168, 3),
    ('stone',   96, 330, 3), ('flower', 132, 344, 2),
    ('mushroom', 20, 470, 3), ('sprout',  60, 500, 2),
    ('tree2',   84, 610, 4), ('bush',    24, 700, 3), ('flower',  70, 726, 3),
    ('log',      6, 900, 3), ('sprout',  96, 918, 2),
    ('flower',  44, 1060, 3), ('flower', 74, 1078, 2), ('stone',   6, 1090, 2),
    ('tree',    92, 1200, 3), ('mushroom', 40, 1268, 2),
    ('bush',    16, 1430, 4), ('sprout',  86, 1470, 3),
    ('stone',   104, 1600, 4), ('flower',  30, 1622, 3),
    ('tree2',   14, 1740, 4), ('bush',    96, 1846, 3), ('flower', 60, 1870, 2),
    ('mushroom', 108, 2010, 3), ('sprout',  20, 2040, 3),
    ('log',     52, 2170, 4), ('flower',  10, 2210, 2),
    ('tree',    88, 2320, 4), ('bush',    18, 2420, 3), ('flower', 66, 2444, 3),
]
RIGHT = [
    ('bush',    28,  50, 4), ('flower',  96,  74, 3), ('sprout', 132,  96, 2),
    ('tree',    86, 180, 4), ('mushroom', 30, 262, 3),
    ('flower',  10, 420, 3), ('stone',   64, 436, 3),
    ('tree2',   20, 540, 3), ('sprout', 104, 604, 3), ('flower', 136, 628, 2),
    ('log',     72, 760, 3), ('bush',     8, 790, 3),
    ('mushroom', 116, 930, 4), ('flower',  36, 962, 3),
    ('tree',    16, 1080, 4), ('bush',    88, 1176, 4), ('sprout', 140, 1200, 2),
    ('stone',   28, 1330, 3), ('flower',  92, 1348, 2),
    ('tree2',  100, 1450, 4), ('mushroom', 22, 1520, 3), ('flower', 62, 1548, 3),
    ('sprout',  10, 1700, 3), ('log',     70, 1720, 4),
    ('bush',   112, 1880, 3), ('flower',  22, 1904, 3),
    ('tree',    46, 2010, 4), ('stone',  120, 2100, 3),
    ('mushroom', 14, 2240, 4), ('sprout',  80, 2280, 3),
    ('bush',    96, 2400, 4), ('flower',  30, 2430, 3), ('flower', 60, 2452, 2),
]


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
        sw, sh = len(rows[0]) * cell, len(rows) * cell
        assert 0 <= px and px + sw <= W, f'{name} 가로가 띠({W}px)를 벗어난다'
        assert 0 <= py and py + sh <= H, f'{name} 세로 반복 경계({H}px)를 넘는다'
        px_map = resolve(rows)
        for (x, y), col in px_map.items():
            if is_night:
                col = night(col)
            rects.append((px + x * cell, py + y * cell, cell, cell, col))
    body = "".join(f"<rect x='{x}' y='{y}' width='{w}' height='{h}' fill='{c}'/>"
                   for x, y, w, h, c in merge(rects))
    return (f"<svg xmlns='http://www.w3.org/2000/svg' width='{W}' height='{H}' "
            f"shape-rendering='crispEdges'>{body}</svg>")


if __name__ == '__main__':
    import pathlib
    out = pathlib.Path(__file__).resolve().parent.parent / 'public' / 'scenery'
    out.mkdir(parents=True, exist_ok=True)
    for tag, items in (('left', LEFT), ('right', RIGHT)):
        for mode, is_night in (('day', False), ('night', True)):
            f = out / f'{tag}-{mode}.svg'
            f.write_text(build(items, is_night), encoding='utf-8')
            print(f'  {f.name}  {len(f.read_text())//1024}KB  오브젝트 {len(items)}개')
