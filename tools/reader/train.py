"""Trains the detector or the recogniser on synthetic pages (synth.py), plus real labelled crops if present.
  python3 train.py det|rec OUTDIR [--minutes 150]"""
import sys, os, time, math, json, glob, argparse
import numpy as np, cv2
import torch, torch.nn as nn, torch.nn.functional as F
from torch.utils.data import IterableDataset, DataLoader
import synth, prep
from model import Detector, Recognizer, export, NCLS
from font import CHARSET

CIDX = {c: i + 1 for i, c in enumerate(CHARSET)}


class Pages(IterableDataset):
    def __init__(s, mode, seed): s.mode, s.seed = mode, seed

    def __iter__(s):
        wi = torch.utils.data.get_worker_info()
        rng = np.random.default_rng(s.seed + (wi.id if wi else 0) * 7919 + int(time.time()))
        real = load_real()
        while True:
            img, words = synth.page(rng)
            g = synth.to_gray(img)
            if s.mode == "det":
                x, (kx, ky) = prep.det_input(g)
                ws = [(t, q * [kx, ky], c) for t, q, c in words]
                m, ign, box = prep.det_targets(ws, *x.shape)
                # random 384 crop (fully convolutional), aligned to cells
                H, W = x.shape; S = 384
                for _ in range(2):
                    y0 = int(rng.integers(0, max(1, (H - S) // 4 + 1))) * 4; x0 = int(rng.integers(0, max(1, (W - S) // 4 + 1))) * 4
                    xs = x[y0:y0 + S, x0:x0 + S]
                    if xs.shape != (S, S):   # landscape page smaller than the crop: pad
                        p = np.full((S, S), 0.0, np.float32); p[:xs.shape[0], :xs.shape[1]] = xs; xs = p
                    sl = (slice(y0 // 4, (y0 + S) // 4), slice(x0 // 4, (x0 + S) // 4))
                    yield (torch.from_numpy(xs.copy())[None], torch.from_numpy(m[sl].copy()), torch.from_numpy(ign[sl].copy()),
                           torch.from_numpy(box[(slice(None),) + sl].copy()))
            else:
                for t, q, carbon in words:
                    if rng.random() < 0.5: continue
                    x0, y0 = q[:, 0].min(), q[:, 1].min(); x1, y1 = q[:, 0].max(), q[:, 1].max()
                    c = prep.crop(g, x0, y0, x1, y1, rng)
                    yield torch.from_numpy(c), [CIDX[ch] for ch in t]
                # negatives: background, rules, half words
                for _ in range(2):
                    h = rng.uniform(8, 40); w = h * rng.uniform(0.5, 4)
                    x0, y0 = rng.uniform(0, g.shape[1] - w), rng.uniform(0, g.shape[0] - h)
                    near = any(abs(q[:, 1].mean() - (y0 + h / 2)) < h and q[:, 0].min() < x0 + w and q[:, 0].max() > x0 for _, q, _ in words)
                    if near: continue
                    yield torch.from_numpy(prep.crop(g, x0, y0, x0 + w, y0 + h, rng)), []
                if real and rng.random() < 0.5:
                    for _ in range(8):
                        c, t = real[rng.integers(len(real))]
                        yield torch.from_numpy(c), [CIDX[ch] for ch in t]


_REAL = None
def load_real():
    """Real crops labelled by hand: real/<anything>.png with the text in real/labels.json."""
    global _REAL
    if _REAL is None:
        _REAL = []
        d = os.path.join(os.path.dirname(__file__), "real")
        if os.path.exists(os.path.join(d, "labels.json")):
            for f, t in json.load(open(os.path.join(d, "labels.json"))).items():
                im = cv2.imread(os.path.join(d, f), cv2.IMREAD_GRAYSCALE)
                if im is not None and all(c in CIDX for c in t): _REAL.append((prep.norm_crop(cv2.resize(im, (max(4, round(im.shape[1] * 32 / im.shape[0] / 4) * 4), 32)).astype(np.float32)), t))
    return _REAL


def collate_rec(batch):
    W = max(c.shape[1] for c, _ in batch)
    x = torch.zeros(len(batch), 1, 32, W)
    for i, (c, _) in enumerate(batch): x[i, 0, :, :c.shape[1]] = c
    tg = torch.tensor([v for _, t in batch for v in t], dtype=torch.long)
    tl = torch.tensor([len(t) for _, t in batch], dtype=torch.long)
    il = torch.tensor([c.shape[1] // 4 for c, _ in batch], dtype=torch.long)
    return x, tg, tl, il


def greedy(logits, il):
    out = []
    a = logits.argmax(1)
    for i in range(a.shape[0]):
        s, prev = "", 0
        for v in a[i, :il[i]].tolist():
            if v != prev and v: s += CHARSET[v - 1]
            prev = v
        out.append(s)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("mode"); ap.add_argument("out"); ap.add_argument("--minutes", type=float, default=150)
    ap.add_argument("--workers", type=int, default=3); ap.add_argument("--resume", default=None)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    torch.set_num_threads(max(1, os.cpu_count() - a.workers))
    det = a.mode == "det"
    model = Detector() if det else Recognizer()
    if a.resume and os.path.exists(a.resume): model.load_state_dict(torch.load(a.resume)); print("resumed", a.resume)
    bs = 12 if det else 48
    dl = DataLoader(Pages(a.mode, 1234), batch_size=bs, num_workers=a.workers, collate_fn=None if det else collate_rec)
    opt = torch.optim.AdamW(model.parameters(), lr=2e-3, weight_decay=1e-4)
    ctc = nn.CTCLoss(blank=0, zero_infinity=True)
    t0 = time.time(); budget = a.minutes * 60; step = 0; avg = None; hits = []
    model.train()
    for batch in dl:
        el = time.time() - t0
        if el > budget: break
        lr = 2e-3 * (0.5 * (1 + math.cos(math.pi * min(1, el / budget)))) + 2e-5
        for g in opt.param_groups: g["lr"] = lr
        if det:
            x, m, ign, box = batch
            y = model(x)
            w = 1 - ign
            lp = F.binary_cross_entropy_with_logits(y[:, 0], m, weight=w * (1 + 2 * m))
            pos = m > 0
            lb = (F.smooth_l1_loss(y[:, 1:5].permute(0, 2, 3, 1)[pos], box.permute(0, 2, 3, 1)[pos], beta=0.05) if pos.any() else y.sum() * 0)
            loss = lp + lb
        else:
            x, tg, tl, il = batch
            y = model(x)[:, :, 0, :]                       # N C T
            loss = ctc(y.permute(2, 0, 1).log_softmax(2), tg, il, tl)
        opt.zero_grad(); loss.backward(); nn.utils.clip_grad_norm_(model.parameters(), 5); opt.step()
        avg = loss.item() if avg is None else 0.98 * avg + 0.02 * loss.item()
        step += 1
        if not det and step % 50 == 0:
            pred = greedy(y.detach(), il); k = 0; truth = []
            for n in tl.tolist(): truth.append("".join(CHARSET[v - 1] for v in tg[k:k + n].tolist())); k += n
            hits.append(np.mean([p == t for p, t in zip(pred, truth)]))
        if step % 100 == 0:
            msg = f"step {step} {el / 60:.1f} min lr {lr:.5f} loss {avg:.4f}"
            if hits: msg += f" word acc {np.mean(hits[-4:]):.3f}"
            print(msg, flush=True)
        if step % 1000 == 0: torch.save(model.state_dict(), f"{a.out}/{a.mode}.pt")
    torch.save(model.state_dict(), f"{a.out}/{a.mode}.pt")
    model.eval()
    layers = export(model, f"{a.out}/{a.mode}")
    json.dump(dict(layers=layers, charset=CHARSET), open(f"{a.out}/{a.mode}.json", "w"), indent=1)
    print("done", step, "steps")


if __name__ == "__main__":
    main()
