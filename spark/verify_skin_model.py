import json
import random
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from train_skin_classifier import features

root = Path(r"c:\Users\juanc\Downloads\skin-disease-datasaet\train_set")
model = json.loads(Path(__file__).resolve().parents[1].joinpath(
    "src/main/resources/datasets/skin-classifier.json").read_text(encoding="utf-8"))
classes = model["classes"]
mean = np.array(model["mean"])
scale = np.array(model["scale"])
trees = model["trees"]


def predict(z):
    acc = np.zeros(len(classes))
    for t in trees:
        node = 0
        left, right, feat, thr, value = t["left"], t["right"], t["feat"], t["thr"], t["value"]
        while left[node] != -1:
            f = feat[node]
            node = left[node] if f >= 0 and z[f] <= thr[node] else right[node]
        leaf = np.array(value[node], dtype=float)
        total = leaf.sum()
        if total > 0:
            acc += leaf / total
    acc /= len(trees)
    i = int(acc.argmax())
    return classes[i], float(acc[i])


ok = 0
for folder in sorted(p for p in root.iterdir() if p.is_dir()):
    imgs = [p for p in folder.iterdir() if p.suffix.lower() in {".jpg", ".jpeg", ".png"}]
    img = random.Random(7).choice(imgs)
    z = (features(img) - mean) / np.where(scale == 0, 1, scale)
    pred, p = predict(z)
    hit = pred == folder.name
    ok += int(hit)
    mark = "OK" if hit else "MISS"
    print(f"{folder.name:32} -> {pred:32} {p:.2f} {mark}")
print("hits", ok, "/", 8)
