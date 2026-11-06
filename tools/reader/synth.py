"""Synthetic photos of range printouts for training the reader.

page(rng) -> (image uint8 HxW gray, words) where words = [(text, quad 4x2 float)] in image pixels.
Strips are printed with dot-matrix glyphs (font.py), copied side by side (the right copy blurred like a
carbon copy), laid on a random background, warped, lit and compressed like a phone photo."""
import numpy as np, cv2
from font import glyph, ARROWS

SHIFT = 4; SC = 1 << SHIFT   # sub-pixel drawing


def dots_for(text, rng, style):
    """Dot centres (in dot pitch units) for a line of text, plus the x-range of every word."""
    adv = style["adv"]; wide = style.get("wide", 1)
    pts, words, x = [], [], 0.0
    cur, x0 = "", None
    for ch in text + " ":
        if ch == " ":
            if cur: words.append((cur, x0, x - (adv - 5) * wide)); cur = ""
            x += adv * wide; continue
        if x0 is None or not cur: x0 = x
        cur += ch
        g = glyph(ch, rng)
        for r, row in enumerate(g):
            for c, on in enumerate(row):
                if on:
                    pts.append((x + c * wide, r))
                    if wide > 1: pts.append((x + c * wide + 1, r))
                    # 9-pin printers fill diagonals with half steps
                    if style["smooth"] and r + 1 < 9:
                        for dc in (-1, 1):
                            if 0 <= c + dc < 5 and g[r + 1][c + dc] and not g[r + 1][c] and not row[c + dc]:
                                pts.append((x + (c + dc / 2) * wide, r + 0.5))
        x += adv * wide
    return pts, words


class Canvas:
    def __init__(self, h, w):
        self.ink = np.zeros((h, w), np.float32)
        self.words = []   # (text, x0, y0, x1, y1) in canvas pixels
        self.deco = np.zeros((h, w), np.float32)

    def line(self, text, x, y, p, rng, style, label=True):
        pts, words = dots_for(text, rng, style)
        r = p * style["dot"]
        jit = style["jitter"]
        for (dx, dy) in pts:
            cx = x + (dx + rng.normal(0, jit)) * p; cy = y + (dy + rng.normal(0, jit)) * p
            cv2.circle(self.ink, (int(cx * SC), int(cy * SC)), max(1, int(r * SC)), 1.0, -1, cv2.LINE_AA, SHIFT)
        if label:
            for t, a, b in words:
                desc = any(c in "gjpqy" for c in t)
                self.words.append((t, x + (a - 0.3) * p, y - 0.3 * p, x + (b + 0.3) * p, y + (8.8 if desc else 6.7) * p))

    def rule(self, x0, x1, y, p, rng, dotted=False):
        """dashed underline or dotted separator (not text)"""
        step = p * (2.0 if dotted else 1.0)
        x = x0
        while x < x1:
            if not dotted or rng.random() < 0.9:
                cv2.circle(self.ink, (int(x * SC), int(y * SC)), max(1, int(p * 0.4 * SC)), 1.0, -1, cv2.LINE_AA, SHIFT)
            x += step


ARROWCH = ">"


def pg_lines(rng):
    """Text lines of a Pg-style strip: list of (kind, text)."""
    L = []
    prog = int(rng.choice([32, 36, 57, 59, 184, 191, 209, 211, rng.integers(1, 400)]))
    tgt = f"{rng.choice([21, 23, 24, rng.integers(1, 40)])}/{rng.integers(1, 60):04d}"
    sp = " " * int(rng.integers(1, 4))
    L.append(("text", (f"Pg{prog}" if rng.random() < 0.6 else f"Pg {prog}") + sp + tgt))
    if rng.random() < 0.4:
        L.append(("text", f"{rng.integers(1, 29):02d}.{rng.integers(1, 13):02d}.26  {rng.integers(7, 22):02d}:{rng.integers(0, 60):02d}"))
        if rng.random() < 0.7:
            L.append(("dots", "")); L.append(("text", f"Stich-Nr:{' ' * int(rng.integers(2, 6))}{prog}"))
    mode = rng.choice(["a10", "a10", "a10", "b4", "a100"], p=[0.3, 0.25, 0.15, 0.15, 0.15])
    if mode == "b4": series = [6, 3, 3, 6] if rng.random() < 0.6 else [6, 6, 6]
    else:
        opts = [[10], [6, 4], [5, 5], [5, 2, 3], [10, 10], [5, 5, 5], [4]]
        series = opts[rng.choice(len(opts), p=[0.3, 0.2, 0.15, 0.1, 0.1, 0.1, 0.05])]
    if mode != "b4" and rng.random() < 0.2:   # sighters
        a, b = rng.integers(6, 11, 2)
        L.append(("row", f" 1 {ARROWCH} {a:>3}")); L.append(("rowsub", f" 2 {ARROWCH} {b:>3} {a + b:>4}"))
    n, tot = 0, 0
    for s in series:
        sub = 0
        for k in range(s):
            n += 1
            if mode == "b4": v = int(rng.choice([4, 4, 4, 3, 3, 2, 0]))
            elif mode == "a100": v = int(rng.integers(60, 101))
            else: v = int(rng.choice([10, 10, 9, 9, 9, 8, 8, 7, 6, 5, 0], p=[.15, .1, .2, .15, .1, .1, .05, .06, .04, .03, .02]))
            sub += v
            arrow = ARROWCH if rng.random() < 0.97 else "+"
            last = k == s - 1 and len(series) > 1
            t = f"{n:>2} {arrow} {v:>3}" + (f" {sub:>4}" if last else "")
            L.append(("rowsub" if last else "row", t))
        tot += sub
    L.append(("big", f"{tot}"))
    if mode != "a100" and rng.random() < 0.7:
        L.append(("text", f"T/P{'' if rng.random() < 0.5 else ' '}{rng.integers(1, 20)} {rng.integers(80, 100):>3}"))
    return L, tot


WORDS = ["Total", "Subtota", "Scheibe", "Benutzer", "Auszeichnung", "Kranzkarte", "Gruppe", "Stich", "Sektion",
         "Muttenz", "Schiessen", "Gewehr", "Feld", "Einzel", "Program", "Kopie", "Original"]


def sius_lines(rng):
    L = [("text", f"Scheibe {rng.integers(1, 30)}/{rng.integers(1, 30)}"),
         ("text", f"{rng.integers(1, 29)}.{rng.integers(1, 13)}.2026 {rng.integers(7, 22):02d}:{rng.integers(0, 60):02d}")]
    if rng.random() < 0.6: L.append(("text", f"{rng.choice(['C1', 'E1', 'A1'])} A10 {rng.choice(WORDS)}"))
    tot, htot, n = 0, 0, 0
    for s in [[10], [6, 4], [5, 5]][rng.integers(3)]:
        st = sh = 0
        for k in range(s):
            n += 1; h = int(rng.integers(60, 101)); v = min(10, h // 10 + (1 if h % 10 >= 5 and h < 100 else 0)) if h >= 10 else 0
            v = max(0, min(10, round(h / 10) if h < 100 else 10))
            st += v; sh += h
            L.append(("row", f"{n:>3} {ARROWCH} {v:>2}{'*' if h >= 100 else ''} {h:>3} K"))
        L.append(("text", f"Subtota {st:>3} {sh:>3} K")); tot += st; htot += sh
    L.append(("text", f"Total {tot:>3} {htot:>3} K"))
    return L, tot


def strip_style(rng, sius=False):
    if sius:
        return dict(adv=6, dot=rng.uniform(0.25, 0.4), jitter=0.04, smooth=False)
    return dict(adv=rng.choice([6, 6, 7]), dot=rng.uniform(0.38, 0.75), jitter=rng.uniform(0.0, 0.08), smooth=rng.random() < 0.7)


def render_strip(rng, p, sius=False):
    """Ink canvas of one strip copy (pitch p pixels)."""
    lines, _ = (sius_lines if sius else pg_lines)(rng)
    style = strip_style(rng, sius)
    lh = p * rng.uniform(10, 13.5)
    cols = 17 if not sius else 18
    w = int(cols * 7 * p + 4 * p); h = int((len(lines) + 3) * lh + 6 * p)
    cv = Canvas(h, w)
    y = 2 * p + rng.uniform(0, lh)
    x0 = 2 * p
    for kind, text in lines:
        if kind == "dots":
            cv.rule(x0, x0 + 13 * style["adv"] * p, y + 4 * p, p, rng, dotted=True); y += lh * 0.7; continue
        if kind == "big":
            st = dict(style, wide=2)
            xx = x0 + rng.uniform(3, 6) * style["adv"] * p
            cv.line(text, xx, y, p, rng, st)
            cv.line(text, xx + 0.5 * p, y, p, rng, st, label=False)      # bold
            cv.line(text, x0 + 13 * style["adv"] * p, y + 1 * p, p, rng, style)
            y += lh; continue
        cv.line(text, x0, y, p, rng, style)
        if kind == "rowsub" and rng.random() < 0.95:
            cv.rule(x0, x0 + len(text) * style["adv"] * p, y + 7.8 * p, p, rng)
        y += lh
    return cv


def background(rng, h, w):
    kind = rng.choice(["wood", "dark", "cloth", "plain", "tray"])
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    if kind == "wood":
        base = np.array(rng.uniform([60, 90, 120], [110, 140, 190]))
        f = rng.uniform(0.01, 0.06); noise = cv2.resize(rng.normal(0, 1, (h // 16 + 1, w // 16 + 1)).astype(np.float32), (w, h))
        t = np.sin((xx * np.cos(0.3) + yy * rng.uniform(-0.2, 0.2)) * f + noise * 3) * 0.5 + 0.5
        img = base[None, None, :] * (0.7 + 0.5 * t[..., None])
    elif kind == "dark":
        img = np.ones((h, w, 3), np.float32) * rng.uniform(15, 60, 3)
    elif kind == "tray":
        img = np.ones((h, w, 3), np.float32) * np.array([rng.uniform(60, 120), rng.uniform(30, 60), rng.uniform(20, 50)])
    elif kind == "cloth":
        n = rng.normal(0, 1, (h, w)).astype(np.float32)
        n = cv2.GaussianBlur(n, (0, 0), rng.uniform(0.6, 2.0))
        img = (np.array(rng.uniform(80, 200, 3))[None, None, :] + n[..., None] * rng.uniform(15, 40))
    else:
        img = np.ones((h, w, 3), np.float32) * rng.uniform(150, 230, 3)
    return np.clip(img, 0, 255)


PAPERS = [(245, 245, 245), (240, 238, 232), (60, 225, 250), (40, 200, 235), (200, 190, 245), (225, 215, 185), (235, 220, 205)]  # BGR
INKS = [(110, 50, 80), (130, 60, 90), (40, 30, 40), (90, 60, 70), (120, 80, 70)]


def page(rng, long_side=1024):
    portrait = rng.random() < 0.8
    H, W = (long_side, int(long_side * 0.75)) if portrait else (int(long_side * 0.75), long_side)
    big_h, big_w = int(H * 1.3), int(W * 1.3)
    img = background(rng, big_h, big_w)
    words = []
    sius = rng.random() < 0.18
    nstrips = 1 if sius else int(rng.choice([1, 1, 1, 2]))
    p = float(np.exp(rng.uniform(np.log(1.5), np.log(5.0))))
    cx = big_w * rng.uniform(0.3, 0.5)
    for si in range(nstrips):
        cv = render_strip(rng, p, sius)
        copies = 1 if sius or rng.random() < 0.3 else 2   # Pg 31 and others print one copy only
        gap = int(rng.uniform(0.0, 3) * p)
        sw, sh = cv.ink.shape[1], cv.ink.shape[0]
        pw = sw * copies + gap * (copies - 1) + int(rng.uniform(2, 10) * p)
        ph = max(sh + int(rng.uniform(0, 60) * p), int(big_h * rng.uniform(0.5, 1.1)))
        x0 = int(cx - pw / 2 + si * (pw + rng.uniform(2, 20) * p)); y0 = int(rng.uniform(-0.2, 0.3) * big_h)
        paper = np.array(PAPERS[rng.integers(len(PAPERS))], np.float32) * rng.uniform(0.85, 1.05)
        if sius: paper = np.array([(235, 225, 200), (220, 215, 215), (120, 230, 245), (230, 235, 240)][rng.integers(4)], np.float32)
        # paper
        ya, yb, xa, xb = max(0, y0), min(big_h, y0 + ph), max(0, x0), min(big_w, x0 + pw)
        if ya >= yb or xa >= xb: continue
        img[ya:yb, xa:xb] = paper
        if rng.random() < 0.3:   # paper grain lit from the side
            g = cv2.GaussianBlur(rng.normal(0, 1, (yb - ya, xb - xa)).astype(np.float32), (0, 0), rng.uniform(0.7, 1.5))
            img[ya:yb, xa:xb] += g[..., None] * rng.uniform(5, 25)
        ink = np.array(INKS[rng.integers(len(INKS))], np.float32)
        if sius: ink = np.array([rng.uniform(80, 150)] * 3, np.float32)
        strength = rng.uniform(0.55, 1.0) if not sius else rng.uniform(0.3, 0.7)
        for c in range(copies):
            a = cv.ink.copy()
            if c == 1:   # carbon copy: blurred, smeared, sometimes doubled
                a = cv2.GaussianBlur(a, (0, 0), rng.uniform(0.3, 1.8) * p / 2)
                if rng.random() < 0.6:
                    sm = cv2.GaussianBlur(np.roll(a, int(rng.uniform(2, 15) * p), 1), (0, 0), p * rng.uniform(1, 3))
                    a = np.maximum(a, sm * rng.uniform(0.3, 0.9))
                a = a * rng.uniform(0.6, 1.0)
            a = np.clip(a * strength, 0, 1)
            ox = x0 + int(rng.uniform(1, 4) * p) + c * (sw + gap); oy = y0 + int(rng.uniform(1, 10) * p)
            ya2, xa2 = max(0, oy), max(0, ox); yb2, xb2 = min(big_h, oy + sh), min(big_w, ox + sw)
            if ya2 >= yb2 or xa2 >= xb2: continue
            sub = a[ya2 - oy:yb2 - oy, xa2 - ox:xb2 - ox][..., None]
            img[ya2:yb2, xa2:xb2] = img[ya2:yb2, xa2:xb2] * (1 - sub) + ink * sub
            for (t, a0, b0, a1, b1) in cv.words:
                q = np.array([[a0, b0], [a1, b0], [a1, b1], [a0, b1]], np.float32) + [ox, oy]
                if q[:, 0].min() >= 0 and q[:, 1].min() >= 0 and q[:, 0].max() < big_w and q[:, 1].max() < big_h:
                    words.append((t, q, c == 1))
    # ribbon smudge: a blurred, smeared imprint of another printout with the fabric texture of the ribbon,
    # often right next to or over the sharp rows (bottom left on many Pg strips). Not text: no words.
    if not sius and rng.random() < 0.55:
        for _ in range(int(rng.integers(1, 3))):
            g = render_strip(rng, p).ink
            g = cv2.GaussianBlur(g, (0, 0), p * rng.uniform(1.2, 2.6))
            k = int(max(3, p * rng.uniform(5, 14))); kern = np.ones((1, k), np.float32) / k
            g = cv2.filter2D(g, -1, kern)                                   # smeared sideways
            gh, gw = g.shape
            yy, xx = np.mgrid[0:gh, 0:gw]
            per = max(2.0, p * rng.uniform(0.6, 1.2))
            fabric = 0.55 + 0.45 * (0.5 + 0.5 * np.sin(xx * 2 * np.pi / per) * np.sin(yy * 2 * np.pi / per))
            g = np.clip(g * fabric * rng.uniform(1.2, 2.5), 0, 1) * rng.uniform(0.25, 0.75)
            if rng.random() < 0.5:                                          # only part of it (a band)
                a0 = int(rng.uniform(0, 0.6) * gh); g[:a0] = 0; g[int(a0 + rng.uniform(0.2, 0.6) * gh):] = 0
            ox = int(cx + rng.uniform(-0.6, 0.3) * gw); oy = int(rng.uniform(-0.1, 0.6) * big_h)
            ya2, xa2 = max(0, oy), max(0, ox); yb2, xb2 = min(big_h, oy + gh), min(big_w, ox + gw)
            if ya2 >= yb2 or xa2 >= xb2: continue
            sub = g[ya2 - oy:yb2 - oy, xa2 - ox:xb2 - ox][..., None]
            col = np.array(INKS[rng.integers(len(INKS))], np.float32) * rng.uniform(0.9, 1.3)
            img[ya2:yb2, xa2:xb2] = img[ya2:yb2, xa2:xb2] * (1 - sub) + col * sub
    # sideways text (name, club) on some strips: should not be read as rows
    if rng.random() < 0.15:
        tmp = Canvas(int(14 * p), int(big_h * 0.8))
        tmp.line(" ".join(rng.choice(WORDS, 3)), 2 * p, 3 * p, p, rng, strip_style(rng))
        rot = cv2.rotate(tmp.ink, cv2.ROTATE_90_CLOCKWISE)
        x = int(rng.uniform(0, big_w - rot.shape[1])); y = int(rng.uniform(0, max(1, big_h - rot.shape[0])))
        sub = rot[:big_h - y, :big_w - x][..., None] * 0.8
        img[y:y + sub.shape[0], x:x + sub.shape[1]] = img[y:y + sub.shape[0], x:x + sub.shape[1]] * (1 - sub) + 50 * sub
    # perspective: random quad of the big canvas onto the output
    src = np.float32([[0, 0], [big_w, 0], [big_w, big_h], [0, big_h]])
    ang = rng.normal(0, 0.05); sc = rng.uniform(0.85, 1.0)
    c, s = np.cos(ang), np.sin(ang)
    dst = []
    for (x, y) in src:
        x2, y2 = (x - big_w / 2) * sc, (y - big_h / 2) * sc
        x3, y3 = c * x2 - s * y2 + W / 2, s * x2 + c * y2 + H / 2
        dst.append([x3 + rng.normal(0, W * 0.02), y3 + rng.normal(0, H * 0.02)])
    M = cv2.getPerspectiveTransform(src, np.float32(dst))
    out = cv2.warpPerspective(img, M, (W, H), flags=cv2.INTER_AREA, borderMode=cv2.BORDER_REPLICATE)
    res = []
    for t, q, carbon in words:
        q2 = cv2.perspectiveTransform(q[None], M)[0]
        if q2[:, 0].min() >= 0 and q2[:, 1].min() >= 0 and q2[:, 0].max() < W and q2[:, 1].max() < H:
            res.append((t, q2, carbon))
    # light: gradient, shadow, glare
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    gx, gy = rng.normal(0, 1, 2)
    light = 1 + 0.25 * rng.random() * ((xx / W - 0.5) * gx + (yy / H - 0.5) * gy)
    if rng.random() < 0.5:   # shadow of the phone or hand
        a = rng.uniform(0, 2 * np.pi); off = rng.uniform(-0.3, 0.3)
        d = (xx / W - 0.5) * np.cos(a) + (yy / H - 0.5) * np.sin(a) - off
        light *= 1 - rng.uniform(0.2, 0.55) * (1 / (1 + np.exp(-d * rng.uniform(20, 120))))
    if rng.random() < 0.2:   # glare
        cx2, cy2 = rng.uniform(0, W), rng.uniform(0, H); r = rng.uniform(0.1, 0.4) * W
        out = out + 80 * rng.random() * np.exp(-((xx - cx2) ** 2 + (yy - cy2) ** 2) / (2 * r * r))[..., None]
    out = out * light[..., None] * rng.uniform(0.75, 1.1)
    # camera: blur, noise, colour balance
    if rng.random() < 0.7: out = cv2.GaussianBlur(out, (0, 0), rng.uniform(0.3, 1.3))
    out = out * rng.uniform(0.9, 1.1, 3)[None, None, :] + rng.normal(0, rng.uniform(1, 6), out.shape)
    out = np.clip(out, 0, 255).astype(np.uint8)
    q = int(rng.uniform(35, 90))
    out = cv2.imdecode(cv2.imencode(".jpg", out, [cv2.IMWRITE_JPEG_QUALITY, q])[1], cv2.IMREAD_COLOR)
    return out, res


def to_gray(bgr):
    """The reader works on grey: the darkest channel would lose purple on yellow, so use luminance."""
    return cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)


if __name__ == "__main__":
    import sys
    rng = np.random.default_rng(int(sys.argv[2]) if len(sys.argv) > 2 else 0)
    for k in range(4):
        im, ws = page(rng)
        v = im.copy()
        for t, q, carbon in ws:
            cv2.polylines(v, [q.astype(np.int32)], True, (0, 0, 255) if not carbon else (255, 0, 0), 1)
        cv2.imwrite(f"{sys.argv[1]}/page{k}.jpg", v)
        print(k, len(ws), [w[0] for w in ws[:14]])
