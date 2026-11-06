"""Reference implementation of the reader (numpy only), the model the apps' code follows.
  python3 infer.py MODELDIR out.json photo1.jpg ...   -> words in the scan-lab format (score.js)"""
import sys, json
import numpy as np, cv2
import prep


class Net:
    def __init__(s, path):
        meta = json.load(open(path + ".json"))
        s.layers = meta["layers"]; s.charset = meta.get("charset")
        blob = np.fromfile(path + ".bin", dtype=np.float16).astype(np.float32)
        for L in s.layers:
            if L["op"] == "conv":
                n = L["cout"] * L["cin"] * L["kh"] * L["kw"]
                d = blob[L["offset"]:L["offset"] + L["count"]]
                L["w"] = d[:n].reshape(L["cout"], L["cin"] * L["kh"] * L["kw"]); L["b"] = d[n:]

    def __call__(s, x):
        """x: C x H x W float32"""
        for L in s.layers:
            if L["op"] == "pool":
                c, h, w = x.shape; kh, kw = L["kh"], L["kw"]
                x = x[:, :h // kh * kh, :w // kw * kw].reshape(c, h // kh, kh, w // kw, kw).max(axis=(2, 4))
                continue
            c, h, w = x.shape
            kh, kw, dh, dw, ph, pw = L["kh"], L["kw"], L["dh"], L["dw"], L["ph"], L["pw"]
            xp = np.pad(x, ((0, 0), (ph, ph), (pw, pw)))
            oh, ow = h + 2 * ph - dh * (kh - 1), w + 2 * pw - dw * (kw - 1)
            cols = np.empty((c, kh, kw, oh, ow), np.float32)
            for i in range(kh):
                for j in range(kw):
                    cols[:, i, j] = xp[:, i * dh:i * dh + oh, j * dw:j * dw + ow]
            y = L["w"] @ cols.reshape(c * kh * kw, oh * ow) + L["b"][:, None]
            if L["relu"]: np.maximum(y, 0, out=y)
            x = y.reshape(-1, oh, ow)
        return x


def decode(logits, charset):
    """greedy CTC; also the mean probability of the chosen characters"""
    z = logits - logits.max(0, keepdims=True); p = np.exp(z); p /= p.sum(0, keepdims=True)
    a = p.argmax(0)
    s, prev, conf = "", 0, []
    for t, v in enumerate(a):
        if v != prev and v: s += charset[v - 1]; conf.append(p[v, t])
        prev = v
    return s, (float(np.mean(conf)) if conf else 0.0)


def read(det, rec, gray):
    x, (kx, ky) = prep.det_input(gray)
    y = det(x[None])
    prob = 1 / (1 + np.exp(-y[0]))
    boxes = prep.det_boxes(prob, y[1:5])
    words = []
    for (x0, y0, x1, y1, sc) in boxes:
        X0, Y0, X1, Y1 = x0 / kx, y0 / ky, x1 / kx, y1 / ky
        c = prep.crop(gray, X0, Y0, X1, Y1)
        t, conf = decode(rec(c[None])[:, 0, :], rec.charset)
        if t: words.append(dict(t=t, box=(X0, Y0, X1, Y1), conf=conf, det=sc))
    return words, prob


if __name__ == "__main__":
    md = sys.argv[1]
    det, rec = Net(md + "/det"), Net(md + "/rec")
    pages = []
    for p in sys.argv[3:]:
        im = cv2.imread(p, cv2.IMREAD_COLOR)
        g = cv2.cvtColor(im, cv2.COLOR_BGR2GRAY)
        H, W = g.shape
        words, _ = read(det, rec, g)
        # scan-lab format: normalized, origin bottom left
        pages.append(dict(file=p.split("/")[-1], words=[dict(t=w["t"], x=w["box"][0] / W, y=1 - w["box"][3] / H,
                                                            w=(w["box"][2] - w["box"][0]) / W, h=(w["box"][3] - w["box"][1]) / H,
                                                            c=round(w["conf"], 3)) for w in words]))
        print(p.split("/")[-1], len(words), " ".join(w["t"] for w in words[:40]), flush=True)
    json.dump(pages, open(sys.argv[2], "w"))
