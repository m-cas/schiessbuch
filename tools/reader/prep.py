"""Image preparation shared by training and inference (the apps do exactly the same steps)."""
import numpy as np, cv2

DET_LONG = 1024      # detector input: long side in pixels
CELL = 4             # detector output cell
BOXN = 32.0          # box distances are predicted in units of 32 px
REC_H = 32           # recogniser crop height
REC_MAXW = 512


def det_input(gray):
    """Grey uint8 photo -> float32 detector input (long side DET_LONG, size a multiple of 4) and the scale used."""
    h, w = gray.shape
    k = DET_LONG / max(h, w)
    nh, nw = max(CELL, int(round(h * k / CELL)) * CELL), max(CELL, int(round(w * k / CELL)) * CELL)
    im = cv2.resize(gray, (nw, nh), interpolation=cv2.INTER_AREA if k < 1 else cv2.INTER_LINEAR)
    return im.astype(np.float32) / 255.0 - 0.5, (nw / w, nh / h)


def det_targets(words, h, w):
    """Training targets on the CELL grid: text mask (cells inside the core of a word) and l, t, r, b distances."""
    gh, gw = h // CELL, w // CELL
    mask = np.zeros((gh, gw), np.float32); ign = np.zeros((gh, gw), np.float32); box = np.zeros((4, gh, gw), np.float32)
    for (t, q, carbon) in words:
        x0, y0 = q[:, 0].min(), q[:, 1].min(); x1, y1 = q[:, 0].max(), q[:, 1].max()
        bh = y1 - y0; cy = (y0 + y1) / 2
        cx0, cx1 = x0 + 0.2 * bh, x1 - 0.2 * bh
        if cx1 < cx0: cx0 = cx1 = (x0 + x1) / 2
        cy0, cy1 = cy - 0.2 * bh, cy + 0.2 * bh
        r0, r1 = int(np.floor(cy0 / CELL)), int(np.floor(cy1 / CELL))
        c0, c1 = int(np.floor(cx0 / CELL)), int(np.floor(cx1 / CELL))
        r0, c0 = max(r0, 0), max(c0, 0); r1, c1 = min(r1, gh - 1), min(c1, gw - 1)
        # ring around the core: don't care
        ign[max(0, r0 - 1):r1 + 2, max(0, c0 - 1):c1 + 2] = 1
        for r in range(r0, r1 + 1):
            for c in range(c0, c1 + 1):
                px, py = (c + 0.5) * CELL, (r + 0.5) * CELL
                mask[r, c] = 1
                box[:, r, c] = [(px - x0) / BOXN, (py - y0) / BOXN, (x1 - px) / BOXN, (y1 - py) / BOXN]
    ign[mask > 0] = 0
    return mask, ign, box


def det_boxes(prob, box, thr=0.5, min_cells=1):
    """Detector output -> word boxes (x0, y0, x1, y1, score) in detector input pixels."""
    on = (prob > thr).astype(np.uint8)
    n, lab, st, _ = cv2.connectedComponentsWithStats(on, connectivity=4)
    out = []
    for i in range(1, n):
        if st[i, 4] < min_cells: continue
        ys, xs = np.where(lab == i)
        px, py = (xs + 0.5) * CELL, (ys + 0.5) * CELL
        wgt = prob[ys, xs]
        x0 = np.average(px - box[0, ys, xs] * BOXN, weights=wgt); y0 = np.average(py - box[1, ys, xs] * BOXN, weights=wgt)
        x1 = np.average(px + box[2, ys, xs] * BOXN, weights=wgt); y1 = np.average(py + box[3, ys, xs] * BOXN, weights=wgt)
        if x1 - x0 < 2 or y1 - y0 < 3: continue
        out.append((float(x0), float(y0), float(x1), float(y1), float(wgt.mean())))
    return out


def crop(gray, x0, y0, x1, y1, rng=None):
    """Word crop for the recogniser: margin around the box, height REC_H, ink bright on dark, contrast stretched."""
    h = y1 - y0
    if rng is not None:
        my0, my1 = rng.uniform(0.05, 0.35, 2) * h; mx0, mx1 = rng.uniform(0.05, 0.5, 2) * h
        dy = rng.normal(0, 0.06) * h
    else:
        my0 = my1 = 0.2 * h; mx0 = mx1 = 0.3 * h; dy = 0
    X0, X1 = x0 - mx0, x1 + mx1; Y0, Y1 = y0 - my0 + dy, y1 + my1 + dy
    ch = Y1 - Y0; cw = X1 - X0
    ow = int(min(REC_MAXW, max(8, round(cw * REC_H / ch))))
    ow = (ow + 3) // 4 * 4
    # affine resample (sub-pixel exact, border replicated)
    M = np.float32([[cw / ow, 0, X0], [0, ch / REC_H, Y0]])
    src = gray
    if ch / REC_H > 2:   # pre-blur against aliasing when shrinking a lot
        s = 0.5 * ch / REC_H
        y_a, y_b = int(max(0, Y0 - 4 * s)), int(min(gray.shape[0], Y1 + 4 * s)) + 1
        x_a, x_b = int(max(0, X0 - 4 * s)), int(min(gray.shape[1], X1 + 4 * s)) + 1
        src = cv2.GaussianBlur(gray[y_a:y_b, x_a:x_b].astype(np.float32), (0, 0), s)
        M = np.float32([[cw / ow, 0, X0 - x_a], [0, ch / REC_H, Y0 - y_a]])
    c = cv2.warpAffine(src.astype(np.float32), M, (ow, REC_H), flags=cv2.INTER_LINEAR | cv2.WARP_INVERSE_MAP, borderMode=cv2.BORDER_REPLICATE)
    return norm_crop(c)


def norm_crop(c):
    lo, hi = np.percentile(c, 3), np.percentile(c, 90)
    return np.clip((hi - c) / max(hi - lo, 12.0), 0, 1).astype(np.float32)
