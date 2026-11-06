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
    for row in rows_of(words):
        for piece in split_copies(row):
            t = [w["t"] for w in piece]
            # header target "21/0004"
            for w in piece:
                for tg in targets:
                    if "/" in w["t"] and lev(w["t"], tg) <= 2: labels.append((w, tg))
            # shot row: number, arrow, value
            if len(piece) >= 3:
                n, arr, v = piece[0], piece[1], piece[2]
                for e in exp:
                    shots = e["shots"]
                    cands = []
                    # number as read, or derived from the value pattern
                    if n["t"].isdigit() and 1 <= int(n["t"]) <= len(shots): cands.append(int(n["t"]))
                    for k in cands:
                        ok_n = True
                        ok_a = arr["t"] == ">"
                        ok_v = v["t"] == str(shots[k - 1])
                        if ok_n + ok_a + ok_v >= 2:
                            labels += [(n, str(k)), (arr, ">"), (v, str(shots[k - 1]))]
                            break
            # total
            for w in piece:
                for e in exp:
                    if w["t"].isdigit() and len(w["t"]) >= 2 and lev(w["t"], str(e["total"])) == 1 and len(w["t"]) == len(str(e["total"])):
                        labels.append((w, str(e["total"])))
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
