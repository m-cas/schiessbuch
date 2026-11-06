"""The two small networks of the reader (PyTorch, training only) and their export.

Detector: grey photo (long side 1024) -> per 4x4 cell: text probability + distances to the word box (l, t, r, b).
Recogniser: one word crop, 32 px high -> per 4 px column: character scores (CTC, blank = 0).

Both use only 3x3 / 1xk convolutions (batch norm folded in at export), ReLU and max pooling, so the
apps run them with a few dozen lines of code (see infer.py for the reference implementation)."""
import json, struct
import numpy as np
import torch, torch.nn as nn
from font import CHARSET

NCLS = len(CHARSET) + 1


def cbr(i, o, k=(3, 3), d=(1, 1), p=None):
    if p is None: p = (d[0] * (k[0] // 2), d[1] * (k[1] // 2))
    return [nn.Conv2d(i, o, k, padding=p, dilation=d, bias=False), nn.BatchNorm2d(o), nn.ReLU(inplace=True)]


class Detector(nn.Module):
    def __init__(s):
        super().__init__()
        s.net = nn.Sequential(
            *cbr(1, 16), nn.MaxPool2d(2),
            *cbr(16, 24), nn.MaxPool2d(2),
            *cbr(24, 24), *cbr(24, 24, d=(2, 2)), *cbr(24, 24, d=(4, 4)), *cbr(24, 24, d=(8, 8)), *cbr(24, 24),
            nn.Conv2d(24, 5, 1))

    def forward(s, x): return s.net(x)


class Recognizer(nn.Module):
    def __init__(s):
        super().__init__()
        s.net = nn.Sequential(
            *cbr(1, 32), nn.MaxPool2d(2),
            *cbr(32, 64), nn.MaxPool2d(2),
            *cbr(64, 96), *cbr(96, 96), nn.MaxPool2d((2, 1)),
            *cbr(96, 128), nn.MaxPool2d((2, 1)),
            *cbr(128, 128, k=(2, 1), p=(0, 0)),
            *cbr(128, 128, k=(1, 3)), *cbr(128, 128, k=(1, 3), d=(1, 2)),
            nn.Conv2d(128, NCLS, 1))

    def forward(s, x): return s.net(x)   # N x C x 1 x W/4


def export(model, path):
    """Writes <path>.json (layer list) and <path>.bin (float16 weights). Batch norm is folded into the convs."""
    layers, blobs, off = [], [], 0
    mods = list(model.net)
    i = 0
    while i < len(mods):
        m = mods[i]
        if isinstance(m, nn.Conv2d):
            w = m.weight.detach().double().numpy(); b = np.zeros(w.shape[0]) if m.bias is None else m.bias.detach().double().numpy()
            relu = False
            if i + 1 < len(mods) and isinstance(mods[i + 1], nn.BatchNorm2d):
                bn = mods[i + 1]
                g = bn.weight.detach().double().numpy() / np.sqrt(bn.running_var.double().numpy() + bn.eps)
                w = w * g[:, None, None, None]; b = (b - bn.running_mean.double().numpy()) * g + bn.bias.detach().double().numpy()
                i += 1
            if i + 1 < len(mods) and isinstance(mods[i + 1], nn.ReLU): relu = True; i += 1
            data = np.concatenate([w.ravel(), b.ravel()]).astype(np.float16)
            layers.append(dict(op="conv", cin=w.shape[1], cout=w.shape[0], kh=w.shape[2], kw=w.shape[3],
                               dh=m.dilation[0], dw=m.dilation[1], ph=m.padding[0], pw=m.padding[1], relu=relu,
                               offset=off, count=int(data.size)))
            blobs.append(data.tobytes()); off += data.size
        elif isinstance(m, nn.MaxPool2d):
            k = m.kernel_size if isinstance(m.kernel_size, tuple) else (m.kernel_size, m.kernel_size)
            layers.append(dict(op="pool", kh=k[0], kw=k[1]))
        i += 1
    with open(path + ".bin", "wb") as f:
        for b in blobs: f.write(b)
    return layers
