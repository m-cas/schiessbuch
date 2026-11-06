"""Labels word crops on the real sample photos from the known results (tools/scan-lab/expected.json),
so the recogniser also learns from real paper. Only photos of the training half are used; the others
stay unseen for the check.
  python3 label_real.py MODELDIR photo1.jpg ...   -> real/<photo>_<n>.png + real/labels.json

A printed shot row is "<n> <arrow> <value> [<subtotal>]". A row is trusted when at least two of the
three parts already read as expected (the shot number in sequence, the arrow, the known value); the
third part is then labelled with the known text. Header words close to the known target ("21/0004")
and the known total are labelled the same way."""
import sys, os, json, re
import numpy as np, cv2
import prep
from infer import Net, read

HERE = os.path.dirname(os.path.abspath(__file__))
EXPECTED = json.load(open(os.path.join(HERE, "../scan-lab/expected.json")))


def lev(a, b):
    d = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        p, d[0] = d[0], i
        for j, cb in enumerate(b, 1):
            p, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, p + (ca != cb))
    return d[-1]


def rows_of(words):
    """group words into printed rows (vertical overlap), each row left to right"""
    ws = sorted(words, key=lambda w: (w["box"][1] + w["box"][3]) / 2)
    rows = []
    for w in ws:
        cy = (w["box"][1] + w["box"][3]) / 2; h = w["box"][3] - w["box"][1]
        if rows:
            r = rows[-1]; rc = np.mean([(v["box"][1] + v["box"][3]) / 2 for v in r]); rh = np.mean([v["box"][3] - v["box"][1] for v in r])
            if abs(cy - rc) < 0.5 * min(h, rh): r.append(w); continue
        rows.append([w])
    return [sorted(r, key=lambda w: w["box"][0]) for r in rows]


def split_copies(row):
    """copies printed side by side: split a row at gaps wider than 3 character heights"""
    out, cur = [], [row[0]]
    for a, b in zip(row, row[1:]):
        h = np.mean([a["box"][3] - a["box"][1], b["box"][3] - b["box"][1]])
        if b["box"][0] - a["box"][2] > 3 * h: out.append(cur); cur = []
        cur.append(b)
    out.append(cur)
    return out


def label_photo(det, rec, path):
    name = os.path.basename(path)
    exp = EXPECTED.get(name)
    if not exp: return []
    g = cv2.cvtColor(cv2.imread(path), cv2.COLOR_BGR2GRAY)
    words, _ = read(det, rec, g)
    labels = []
    targets = [e["target"] for e in exp if e["target"]]
    cands = []   # shot rows: (n, piece)
    for row in rows_of(words):
        for piece in split_copies(row):
            # header target "21/0004": only when one target is clearly the closest
            for w in piece:
                if "/" not in w["t"] or not targets: continue
                d = sorted((lev(w["t"], tg), tg) for tg in targets)
                if d[0][0] <= 2 and (len(d) == 1 or d[1][0] > d[0][0]): labels.append((w, d[0][1]))
            t = [w["t"] for w in piece]
            if len(piece) >= 3 and t[0].isdigit() and 1 <= int(t[0]) <= 40 and t[1] in (">", "+") and t[2].isdigit():
                cands.append((int(t[0]), piece))
    # columns of shot rows (one per copy), top to bottom
    cols = []
    for n, piece in sorted(cands, key=lambda c: c[1][0]["box"][1]):
        x, h = piece[0]["box"][0], piece[0]["box"][3] - piece[0]["box"][1]
        col = next((c for c in cols if abs(c["x"] - x) < 2.5 * h), None)
        if col is None: col = dict(x=x, rows=[]); cols.append(col)
        col["rows"].append((n, piece))
    for col in cols:
        rows = col["rows"]
        seq = [r for i, r in enumerate(rows)
               if (i > 0 and rows[i - 1][0] == r[0] - 1) or (i + 1 < len(rows) and rows[i + 1][0] == r[0] + 1)]
        if len(seq) < 3: continue
        # which printout: the one whose values agree most with what was read
        def agree(e): return sum(1 for n, pc in seq if n <= len(e["shots"]) and pc[2]["t"] == str(e["shots"][n - 1]))
        e = max(exp, key=agree)
        if agree(e) < 0.6 * len(seq): continue
        for n, pc in seq:
            if n > len(e["shots"]): continue
            labels += [(pc[0], str(n)), (pc[1], pc[1]["t"]), (pc[2], str(e["shots"][n - 1]))]
    # crops at the recogniser's geometry (raw grey, as dump_crops.py)
    out = []
    seen = set()
    for w, t in labels:
        X0, Y0, X1, Y1 = w["box"]; key = (round(X0), round(Y0))
        if key in seen: continue
        seen.add(key)
        h = Y1 - Y0
        raw = g[int(max(0, Y0 - 0.2 * h)):int(Y1 + 0.2 * h) + 1, int(max(0, X0 - 0.3 * h)):int(X1 + 0.3 * h) + 1]
        if raw.size: out.append((raw, t, w["t"]))
    return out


if __name__ == "__main__":
    md = sys.argv[1]
    det, rec = Net(md + "/det"), Net(md + "/rec")
    d = os.path.join(HERE, "real"); os.makedirs(d, exist_ok=True)
    lf = os.path.join(d, "labels.json")
    labels = json.load(open(lf)) if os.path.exists(lf) else {}
    changed = 0
    for p in sys.argv[2:]:
        stem = os.path.basename(p).split(".")[0]
        for k in [k for k in labels if k.startswith(stem + "_")]: del labels[k]
        crops = label_photo(det, rec, p)
        for i, (raw, t, was) in enumerate(crops):
            f = f"{stem}_{i:03d}.png"
            cv2.imwrite(os.path.join(d, f), raw); labels[f] = t
            changed += t != was
        print(stem, len(crops), "crops,", sum(t != was for _, t, was in crops), "corrected", flush=True)
    json.dump(labels, open(lf, "w"), indent=0, sort_keys=True)
    print(len(labels), "labelled crops,", changed, "differ from what the model read")
