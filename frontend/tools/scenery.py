#!/usr/bin/env python3
"""들판 오브젝트(public/scenery/*.svg)를 만드는 생성기.

    python3 tools/scenery.py

산출물은 사각형 수백 개짜리 SVG 라 손으로 고칠 수 없다. 모양이나 색을 바꾸려면
반드시 이 파일을 고치고 다시 돌려라. 결과는 public/scenery/ 에 덮어쓴다.

배치 규칙 — 오브젝트는 본문 판(.px) 바깥 여백에만 놓는다. 글자 위에 절대 올리지
않는다. 화면이 좁아 여백이 사라지면 pixel.css 의 미디어쿼리가 통째로 끈다.
"""
import math, urllib.parse

def hexv(h): h=h.lstrip('#'); return tuple(int(h[i:i+2],16) for i in (0,2,4))
def tohex(t): return '#%02x%02x%02x' % tuple(max(0,min(255,round(c))) for c in t)
def mix(a,b,k):
    A,B=hexv(a),hexv(b); return tohex(tuple(A[i]*k+B[i]*(1-k) for i in range(3)))

class Canvas:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.px = {}                       # (x,y) -> 색
    def set(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h: self.px[(x,y)] = c

def disc(cx, cy, r):
    """가장자리를 살짝 깎아 각지지 않게 한 원."""
    out=set()
    for y in range(int(cy-r)-1, int(cy+r)+2):
        for x in range(int(cx-r)-1, int(cx+r)+2):
            if (x-cx)**2 + (y-cy)**2 <= r*r + 0.2: out.add((x,y))
    return out

def ring(cx, cy, r, t=1.0):
    """도넛. 나이테처럼 '선'으로 그려야 할 때 쓴다 — 원을 채우면 얼룩이 된다."""
    return disc(cx,cy,r) - disc(cx,cy,r-t)

def rect(x0,y0,x1,y1):
    return {(x,y) for y in range(y0,y1) for x in range(x0,x1)}

def shade(canvas, mask, tones, light=None, cut=(0.40, 0.68), outline=True):
    """mask 를 3단계로 칠하고 테두리를 외곽선으로 바꾼다.
       밝기를 직선 램프가 아니라 '광원까지의 거리'로 잰다 — 띠가 곡선이 되어
       평평한 사선 대신 덩어리진 입체로 보인다.
       tones = (하이라이트, 기본, 그늘, 외곽선)
       outline=False — 줄기처럼 얇은 도형은 외곽선이 속을 다 먹으므로 끈다."""
    if not mask: return
    xs=[p[0] for p in mask]; ys=[p[1] for p in mask]
    x0,x1,y0,y1 = min(xs),max(xs),min(ys),max(ys)
    rad = max(x1-x0, y1-y0)/2 or 1
    lx, ly = light if light else (x0 - rad*0.35, y0 - rad*0.45)
    span = max(math.hypot(x-lx, y-ly) for (x,y) in mask) or 1
    hi, base, lo, out = tones
    for (x,y) in mask:
        d = math.hypot(x-lx, y-ly) / span
        canvas.set(x, y, hi if d < cut[0] else (base if d < cut[1] else lo))
    if outline:
        for (x,y) in mask:
            if any((x+dx, y+dy) not in mask for dx,dy in ((1,0),(-1,0),(0,1),(0,-1))):
                canvas.set(x, y, out)

def emit(canvas, cell=4):
    """가로로 이어지는 같은 색을 한 사각형으로 묶는다."""
    rects=[]
    for y in range(canvas.h):
        run=None
        for x in range(canvas.w+1):
            c = canvas.px.get((x,y))
            if run and run[2]==c: run[1]+=1; continue
            if run: rects.append((run[0]*cell, y*cell, run[1]*cell, cell, run[2]))
            run=[x,1,c] if c else None
    return rects


# ── 재료별 4단계 (하이라이트 / 기본 / 그늘 / 외곽선) ──
LEAF  = ('#c8e89f', '#93c96d', '#65a049', '#3f6b33')
LEAF2 = ('#bfe3b0', '#84c48c', '#579463', '#376244')   # 다른 종류의 나무
WOOD  = ('#c0925c', '#9a6f42', '#78522f', '#4e3520')
STONE = ('#dcdbcd', '#b2b1a2', '#8a8a7b', '#5d5d52')
CAP   = ('#f7c0c6', '#eb95a3', '#cf6f80', '#8e4453')
STEM  = ('#fdf6e6', '#ecdcc0', '#cfbb9a', '#8d7a5e')
BERRY = ('#f2919f', '#df6b7f', '#c04f63', '#7d3040')
PETAL = ('#f9c6d6', '#f2a3bb', '#d97e9a', '#8e4a63')
GOLD  = ('#fbe6a8', '#f3d07c', '#d9ac52', '#8d6a2c')

def tree(kind=LEAF, trunk=WOOD):
    cv = Canvas(22, 26)
    # 작은 원을 둘레에 여러 개 붙여 잎 뭉치가 물결지는 실루엣을 만든다
    canopy = disc(10.5,9,6.0)
    for (a, r) in ((0,3.5),(55,3.2),(110,3.4),(165,3.0),(215,3.3),(270,3.1),(320,3.4)):
        rad = math.radians(a)
        canopy |= disc(10.5 + math.cos(rad)*5.4, 9 + math.sin(rad)*4.6, r)
    trunk_m = rect(9,16,13,22) | rect(8,22,14,24)   # 곧은 기둥 + 밑동 한 단
    shade(cv, trunk_m, trunk, light=(7.5,16), cut=(0.45,0.72))
    shade(cv, canopy, kind)
    return cv

def bush(kind=LEAF, berry=BERRY):
    cv = Canvas(16, 11)
    m = disc(5,6,3.9) | disc(10,6,3.9) | disc(7.5,4.5,3.6)
    shade(cv, m, kind)
    for (bx,by) in ((4,4),(9,3),(11,6),(6,7)):
        if (bx,by) in m:
            cv.set(bx,by,berry[1]); cv.set(bx,by-1,berry[3])
            cv.set(bx-1,by,berry[3]); cv.set(bx+1,by,berry[3]); cv.set(bx,by+1,berry[3])
            cv.set(bx,by,berry[1])
    return cv

def mushroom():
    cv = Canvas(14, 15)
    stem_m = rect(5,7,9,13) | disc(6.5,12.5,2.2)
    cap_m  = {p for p in (disc(6.5,6,5.6) | disc(3,6,3.4) | disc(10,6,3.4)) if p[1] <= 7}
    shade(cv, stem_m, STEM, light=(-0.5,-0.15), cut=(-0.5,0.05))
    shade(cv, cap_m, CAP)
    for (sx,sy) in ((4,4),(8,3),(10,5),(6,6)):     # 갓 반점
        if (sx,sy) in cap_m and cv.px.get((sx,sy)) != CAP[3]:
            cv.set(sx,sy,STEM[0])
    return cv

def stone():
    cv = Canvas(14, 9)
    m = disc(5,5,3.6) | disc(9,5.5,3.2) | disc(7,4,3.2)
    m = {p for p in m if p[1] <= 8}
    shade(cv, m, STONE)
    return cv

def stump():
    cv = Canvas(15, 13)
    body = (rect(2,5,13,11) | disc(7.5,10,5.0))
    body = {p for p in body if 5 <= p[1] <= 11}
    top  = {p for p in disc(7.5,5,5.2) if p[1] <= 6}
    shade(cv, body, WOOD, light=(1,4))
    # 잘린 윗면은 속살이라 밝다
    inner = (WOOD[0], WOOD[0], WOOD[1], WOOD[3])
    shade(cv, top, inner, light=(5,3), cut=(0.75, 0.95))
    # 나이테는 '선'으로 그린다. 원을 채우면 얼룩이 된다.
    for r, col in ((3.6, WOOD[2]), (2.0, WOOD[2])):
        for pt in ring(7.5, 5, r, 1.0):
            if pt in top and cv.px.get(pt) != WOOD[3]: cv.set(*pt, col)
    # 세로 나뭇결 두 줄
    for x in (5, 10):
        for y in range(7, 11):
            if (x,y) in body and cv.px.get((x,y)) != WOOD[3]: cv.set(x,y,WOOD[2])
    return cv

def blossom(cv, x, y, pal, core=GOLD):
    """5x5 꽃 한 송이. 바깥은 외곽선, 안쪽 3x3 은 꽃잎, 가운데는 꽃술.
       disc 로 그리면 이 크기에서는 그냥 네모가 된다."""
    for dx, dy in ((0,-2),(-1,-2),(1,-2),(-2,0),(-2,-1),(-2,1),
                   (2,0),(2,-1),(2,1),(0,2),(-1,2),(1,2)):
        cv.set(x+dx, y+dy, pal[3])
    for dx, dy in ((0,-1),(-1,-1),(1,-1),(-1,0),(1,0),(0,1),(-1,1),(1,1)):
        cv.set(x+dx, y+dy, pal[1])
    cv.set(x-1, y-1, pal[0])
    cv.set(x, y, core[1])

def flowers():
    cv = Canvas(17, 15)
    for (sx, sy, pal) in ((4,4,PETAL), (9,3,GOLD), (13,6,PETAL)):
        stem = {(sx, yy) for yy in range(sy+2, 14)}
        for pt in stem: cv.set(*pt, LEAF[2])
        cv.set(sx, 13, LEAF[3])
        blossom(cv, sx, sy, pal, core=GOLD if pal is PETAL else PETAL)
    leaf = disc(6,12,2.1) | disc(11,12,2.1)
    leaf = {p for p in leaf if p[1] <= 14}
    shade(cv, leaf, LEAF)
    return cv

SHAPES = {'tree': tree, 'tree2': lambda: tree(LEAF2), 'bush': bush,
          'mushroom': mushroom, 'stone': stone, 'stump': stump, 'flowers': flowers}

def night_of(cv, base='#2b3446', keep=0.5):
    n = Canvas(cv.w, cv.h)
    n.px = {p: mix(c, base, keep) for p, c in cv.px.items()}
    return n


# ── 여백에 놓는 배치 ──────────────────────────────────────────────
# (이름, 왼쪽 px, 위 px, 도트 크기).  도트 크기를 섞으면 원근이 생기고
# 세로 반복도 덜 눈에 띈다. 타일이 세로로 반복되므로 위아래 경계를 넘으면 안 된다.
W, H = 170, 1400
LEFT = [('tree',20,30,4), ('flowers',100,160,3), ('bush',48,250,4),
        ('mushroom',8,330,4), ('stone',96,420,3), ('tree2',30,490,3),
        ('flowers',22,620,4), ('stump',100,700,3), ('bush',12,790,3),
        ('mushroom',104,860,3), ('tree',36,950,4), ('stone',12,1090,4),
        ('flowers',96,1170,3), ('bush',30,1270,4)]
RIGHT = [('bush',44,40,4), ('mushroom',12,120,3), ('tree2',52,200,4),
         ('flowers',6,340,3), ('stump',96,420,4), ('stone',20,500,3),
         ('tree',44,570,3), ('bush',10,720,4), ('flowers',100,800,4),
         ('mushroom',40,900,4), ('stone',102,990,4), ('tree2',18,1070,4),
         ('bush',96,1210,3), ('stump',20,1290,3)]


def merge_vertical(rects):
    """가로로 묶은 사각형을 세로로도 합친다 — 파일이 절반으로 줄어든다."""
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


def build(items, night):
    rects = []
    for name, px, py, cell in items:
        cv = SHAPES[name]()
        if night:
            cv = night_of(cv)
        assert px >= 0 and px + cv.w * cell <= W, (name, '가로가 띠를 벗어난다')
        assert py >= 0 and py + cv.h * cell <= H, (name, '세로 반복 경계를 넘는다')
        for x, y, rw, rh, c in emit(cv, cell):
            rects.append((x + px, y + py, rw, rh, c))
    body = "".join(f"<rect x='{x}' y='{y}' width='{w}' height='{h}' fill='{c}'/>"
                   for x, y, w, h, c in merge_vertical(rects))
    return (f"<svg xmlns='http://www.w3.org/2000/svg' width='{W}' height='{H}' "
            f"shape-rendering='crispEdges'>{body}</svg>")


if __name__ == '__main__':
    import pathlib
    out = pathlib.Path(__file__).resolve().parent.parent / 'public' / 'scenery'
    out.mkdir(parents=True, exist_ok=True)
    for tag, items in (('left', LEFT), ('right', RIGHT)):
        for mode, night in (('day', False), ('night', True)):
            f = out / f'{tag}-{mode}.svg'
            f.write_text(build(items, night), encoding='utf-8')
            print(f'{f.relative_to(out.parent.parent)}  {len(f.read_text())//1024}KB')
