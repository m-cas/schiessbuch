"""Makes preprocessed copies of the sample photos (tools/scan-samples) so the scan lab can compare
which preparation the text recognition reads best.   python3 variants.py <in_dir> <out_dir>"""
import sys, os, glob
import numpy as np
from PIL import Image, ImageFilter, ImageOps

def otsu(a):
    h, _ = np.histogram(a, bins=256, range=(0, 256)); p = h / h.sum(); w = np.cumsum(p); m = np.cumsum(p * np.arange(256))
    s = (m[-1] * w - m) ** 2 / (w * (1 - w) + 1e-9); return int(np.argmax(s))

def flat(im):
    """grey, uneven lighting removed (divide by a heavily blurred copy)"""
    g = ImageOps.grayscale(im)
    a = np.asarray(g).astype(float); bg = np.asarray(g.filter(ImageFilter.GaussianBlur(40))).astype(float) + 1
    return Image.fromarray(np.clip(a / bg * 235, 0, 255).astype("uint8"))

def variants(im):
    f = flat(im); out = {"v0": im}
    out["v1"] = ImageOps.autocontrast(f, cutoff=1)
    out["v2"] = ImageOps.autocontrast(f.filter(ImageFilter.MinFilter(5)), cutoff=1)
    out["v3"] = ImageOps.autocontrast(f.filter(ImageFilter.GaussianBlur(3)), cutoff=1)
    d = f.filter(ImageFilter.MinFilter(3)).filter(ImageFilter.GaussianBlur(1.5)); a = np.asarray(d)
    out["v4"] = Image.fromarray(((a > otsu(a)) * 255).astype("uint8"))
    out["v5"] = ImageOps.autocontrast(f.resize((f.width // 2, f.height // 2), Image.LANCZOS), cutoff=1)
    out["v6"] = ImageOps.autocontrast(f.filter(ImageFilter.MinFilter(5)).filter(ImageFilter.GaussianBlur(2)), cutoff=1)
    out["v7"] = ImageOps.autocontrast(f.resize((int(f.width * .35), int(f.height * .35)), Image.LANCZOS), cutoff=1)
    return out

def row_crops(p):
    """the photo cut into its printed rows (deskewed), one image per row"""
    sys.path.insert(0, os.path.dirname(__file__))
    from rows import analyse
    im, f, k, a, mr, rs = analyse(p)
    rot = im.rotate(-a, resample=Image.BICUBIC, fillcolor=(255, 255, 255))
    out = []
    for (y0, y1) in rs:
        pad = int((y1 - y0) * 0.35)
        out.append(rot.crop((0, max(0, int((y0 - pad) / k)), rot.width, min(rot.height, int((y1 + pad) / k)))))
    return out

src, dst = sys.argv[1], sys.argv[2]
mode = sys.argv[3] if len(sys.argv) > 3 else "variants"
os.makedirs(dst, exist_ok=True)
for p in sorted(glob.glob(os.path.join(src, "*.jpg"))):
    n = os.path.splitext(os.path.basename(p))[0]
    if mode == "rows":
        Image.open(p).convert("RGB").save(os.path.join(dst, f"{n}_v0.jpg"), quality=90)
        for i, c in enumerate(row_crops(p)):
            c.save(os.path.join(dst, f"{n}_r{i:02d}.jpg"), quality=92)
    else:
        for k, v in variants(Image.open(p).convert("RGB")).items():
            v.save(os.path.join(dst, f"{n}_{k}.jpg"), quality=90)
print("written")
