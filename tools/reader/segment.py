"""Finds text segments (pieces of printed lines) on a photo of a range printout.
Prototype for the trained reader; the same steps are ported to the apps."""
import sys, json
import numpy as np, cv2

def load(path, long_side=1600):
    im = cv2.imread(path, cv2.IMREAD_COLOR)
    k = long_side / max(im.shape[:2])
    if k < 1: im = cv2.resize(im, None, fx=k, fy=k, interpolation=cv2.INTER_AREA)
    return im

def ink(im):
    g = cv2.cvtColor(im, cv2.COLOR_BGR2GRAY).astype(np.float32)
    bg = cv2.dilate(g, np.ones((21, 21), np.uint8))
    bg = cv2.blur(bg, (21, 21))
    d = (bg - g) / np.maximum(bg, 40)          # relative darkness against the local paper
    d[bg < 90] = 0                              # dark table / shadows: no paper there
    return d

def segments(im, dbg=None):
    d = ink(im)
    # local threshold: faint thermal print next to dark sideways text still counts
    loc = cv2.blur(cv2.dilate(d, np.ones((41, 41), np.uint8)), (41, 41))
    noise = float(np.percentile(d[d > 0], 90)) if (d > 0).any() else 0.05
    m = ((d > max(0.07, 1.4 * noise)) & (d > 0.45 * loc)).astype(np.uint8)
    n, lab, st, _ = cv2.connectedComponentsWithStats(m, 8)
    # drop big blobs (edges, pens, shadows)
    big = np.where((st[:, 3] > 60) | (st[:, 2] > 80))[0]
    big = big[big > 0]
    m[np.isin(lab, big)] = 0
    # character height: fuse dots, look at component heights
    f = cv2.morphologyEx(m, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))
    n, lab, st, _ = cv2.connectedComponentsWithStats(f, 8)
    mass = np.bincount(lab.ravel(), weights=d.ravel(), minlength=n)
    ok = [i for i in range(1, n) if 8 <= st[i, 3] <= 70 and st[i, 4] > 15]
    if ok:   # height of the components carrying most ink (paper texture has little)
        hs = np.array([st[i, 3] for i in ok], float); ws = np.array([mass[i] for i in ok])
        o = np.argsort(hs); c = np.cumsum(ws[o]); ch = float(hs[o][np.searchsorted(c, c[-1] / 2)])
    else: ch = 16.0
    # merge characters of a line piece
    kx = max(3, int(ch * 0.9)); ky = max(1, int(ch * 0.15))
    g = cv2.dilate(f, np.ones((2 * ky + 1, kx), np.uint8))
    n, lab, st, _ = cv2.connectedComponentsWithStats(g, 8)
    out = []
    extra = []
    items = [tuple(st[i][:4]) for i in range(1, n)]
    while items or extra:
        if not items: items, extra = extra, []
        x, y, w, h = items.pop()
        boxes = [(y, h)]
        # split at wide horizontal gaps (copies side by side, noise bridges)
        colp = f[y:y + h, x:x + w].sum(0) > 0
        parts, start, gap = [], None, 0
        for k, v in enumerate(list(colp) + [False] * int(ch * 2)):
            if v:
                if start is None: start = k
                gap = 0; end = k
            elif start is not None:
                gap += 1
                if gap > ch * 1.3: parts.append((start, end + 1)); start = None
        if len(parts) > 1:
            for a, b in parts:
                rows = np.where(f[y:y + h, x + a:x + b].sum(1) > 0)[0]
                extra.append((x + a, y + rows[0], b - a, rows[-1] - rows[0] + 1))
            continue
        if h > ch * 1.5:   # text row and dashed underline (or two rows) merged: split at empty rows
            prof = f[y:y + h, x:x + w].sum(1)
            on = prof > max(1, prof.max() * 0.04)
            boxes, start = [], None
            for k, v in enumerate(list(on) + [False]):
                if v and start is None: start = k
                if not v and start is not None: boxes.append((y + start, k - start)); start = None
        for yy, hh in boxes:
            if hh < ch * 0.55 or hh > ch * 2.2 or w < ch * 0.4: continue
            if f[yy:yy + hh, x:x + w].sum() < ch * 2: continue
            out.append((int(x), int(yy), int(w), int(hh)))
    if dbg is not None:
        v = im.copy()
        for x, y, w, h in out: cv2.rectangle(v, (x, y), (x + w, y + h), (0, 0, 255), 1)
        cv2.imwrite(dbg, v)
    return ch, out

if __name__ == "__main__":
    for p in sys.argv[2:]:
        im = load(p)
        ch, segs = segments(im, sys.argv[1] + "/" + p.split("/")[-1])
        print(p.split("/")[-1], im.shape[:2], "char", ch, "segments", len(segs))
