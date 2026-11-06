"""Cuts the word crops the reader finds on real photos, with its guess, for labelling by hand.
  python3 dump_crops.py MODELDIR OUTDIR photo1.jpg ...
Writes OUTDIR/<photo>_<n>.png (the crop as the recogniser sees it, before contrast stretching),
OUTDIR/guess.json and contact sheets OUTDIR/sheet_<k>.png (number, guess, crop)."""
import sys, json, os
import numpy as np, cv2
import prep
from infer import Net, decode

md, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
det, rec = Net(md + "/det"), Net(md + "/rec")
guess, tiles = {}, []
for p in sys.argv[3:]:
    g = cv2.cvtColor(cv2.imread(p), cv2.COLOR_BGR2GRAY)
    x, (kx, ky) = prep.det_input(g)
    y = det(x[None]); prob = 1 / (1 + np.exp(-y[0]))
    stem = os.path.basename(p).split(".")[0]
    for i, (x0, y0, x1, y1, sc) in enumerate(prep.det_boxes(prob, y[1:5])):
        X0, Y0, X1, Y1 = x0 / kx, y0 / ky, x1 / kx, y1 / ky
        c = prep.crop(g, X0, Y0, X1, Y1)
        t, conf = decode(rec(c[None])[:, 0, :], rec.charset)
        # raw grey crop (same geometry) for training
        h = Y1 - Y0
        raw = g[int(max(0, Y0 - 0.2 * h)):int(Y1 + 0.2 * h) + 1, int(max(0, X0 - 0.3 * h)):int(X1 + 0.3 * h) + 1]
        if raw.size == 0: continue
        name = f"{stem}_{i:03d}.png"
        cv2.imwrite(os.path.join(out, name), raw)
        guess[name] = t
        tiles.append((name, t, (c * 255).astype(np.uint8)))
json.dump(guess, open(os.path.join(out, "guess.json"), "w"), indent=0, ensure_ascii=False)
# contact sheets: 3 columns x 40 rows
per = 120
for k in range(0, len(tiles), per):
    rows = []
    for name, t, c in tiles[k:k + per]:
        tile = np.full((36, 420), 255, np.uint8)
        cv2.putText(tile, f"{name[:-4]} {t!r}"[:24], (2, 22), cv2.FONT_HERSHEY_SIMPLEX, 0.5, 0, 1)
        cw = min(c.shape[1], 200); tile[2:34, 215:215 + cw] = 255 - c[:, :cw]
        rows.append(tile)
    while len(rows) % 3: rows.append(np.full((36, 420), 255, np.uint8))
    cols = [np.vstack(rows[i::3]) for i in range(3)]
    cv2.imwrite(os.path.join(out, f"sheet_{k // per}.png"), np.hstack(cols))
print(len(tiles), "crops")
