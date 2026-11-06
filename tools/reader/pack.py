"""Combines the trained detector and recogniser into the files the apps ship:
reader.json (layers of both networks, charset) and reader.bin (float16 weights, detector first).
  python3 pack.py MODELDIR OUTDIR"""
import sys, json, shutil, os

md, out = sys.argv[1], sys.argv[2]
det, rec = json.load(open(md + "/det.json")), json.load(open(md + "/rec.json"))
dbin, rbin = open(md + "/det.bin", "rb").read(), open(md + "/rec.bin", "rb").read()
shift = len(dbin) // 2
for L in rec["layers"]:
    if L["op"] == "conv": L["offset"] += shift
meta = dict(version=1, charset=rec["charset"], det=det["layers"], rec=rec["layers"],
            detLong=1024, cell=4, boxUnit=32, recHeight=32, recMaxWidth=512)
os.makedirs(out, exist_ok=True)
json.dump(meta, open(out + "/reader.json", "w"), separators=(",", ":"))
open(out + "/reader.bin", "wb").write(dbin + rbin)
print("reader.bin", (len(dbin) + len(rbin)) // 1024, "KB")
