"""Entrena un ExtraTrees de piel (8 clases) y exporta el bosque en JSON para Java."""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np
from PIL import Image
from sklearn.ensemble import ExtraTreesClassifier
from sklearn.metrics import classification_report
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import StandardScaler

TRAIN = Path(r"c:\Users\juanc\Downloads\skin-disease-datasaet\train_set")
OUT = Path(__file__).resolve().parents[1] / "src" / "main" / "resources" / "datasets" / "skin-classifier.json"
CACHE = Path(__file__).resolve().parents[1] / "src" / "main" / "resources" / "datasets" / "skin-features.npz"
SIZE = 64
BINS = 16
GRID = 8

LABELS_ES = {
    "BA- cellulitis": "Celulitis bacteriana",
    "BA-impetigo": "Impétigo",
    "FU-athlete-foot": "Pie de atleta",
    "FU-nail-fungus": "Onicomicosis (hongo de uña)",
    "FU-ringworm": "Tiña (ringworm)",
    "PA-cutaneous-larva-migrans": "Larva migrans cutánea",
    "VI-chickenpox": "Varicela",
    "VI-shingles": "Herpes zóster",
}
RIESGO = {
    "BA- cellulitis": "ALTO",
    "BA-impetigo": "MEDIO",
    "FU-athlete-foot": "BAJO",
    "FU-nail-fungus": "BAJO",
    "FU-ringworm": "MEDIO",
    "PA-cutaneous-larva-migrans": "MEDIO",
    "VI-chickenpox": "MEDIO",
    "VI-shingles": "ALTO",
}


def rgb_to_hsv(arr: np.ndarray) -> np.ndarray:
    rf = arr[:, :, 0] / 255.0
    gf = arr[:, :, 1] / 255.0
    bf = arr[:, :, 2] / 255.0
    mx = np.maximum(np.maximum(rf, gf), bf)
    mn = np.minimum(np.minimum(rf, gf), bf)
    d = mx - mn
    h = np.zeros_like(mx)
    mask = d > 1e-8
    r_max = mask & (mx == rf)
    g_max = mask & (mx == gf) & ~r_max
    b_max = mask & ~r_max & ~g_max
    h[r_max] = ((gf[r_max] - bf[r_max]) / d[r_max]) % 6
    h[g_max] = (bf[g_max] - rf[g_max]) / d[g_max] + 2
    h[b_max] = (rf[b_max] - gf[b_max]) / d[b_max] + 4
    h = (h / 6.0) % 1.0
    s = np.divide(d, mx, out=np.zeros_like(mx), where=mx > 1e-8)
    return np.stack([h * 255.0, s * 255.0, mx * 255.0], axis=2)


def hist_channel(channel: np.ndarray) -> np.ndarray:
    h, _ = np.histogram(channel, bins=BINS, range=(0, 256), density=True)
    return h.astype(np.float32)


def features(path: Path) -> np.ndarray:
    img = Image.open(path).convert("RGB").resize((SIZE, SIZE))
    arr = np.asarray(img, dtype=np.float32)
    hist = [hist_channel(arr[:, :, c]) for c in range(3)]
    hsv = rgb_to_hsv(arr)
    hist += [hist_channel(hsv[:, :, c]) for c in range(3)]
    gray = arr.mean(axis=2)
    cell = SIZE // GRID
    tex = []
    for i in range(GRID):
        for j in range(GRID):
            tex.append(gray[i * cell:(i + 1) * cell, j * cell:(j + 1) * cell].mean() / 255.0)
    means = arr.reshape(-1, 3).mean(axis=0) / 255.0
    stds = arr.reshape(-1, 3).std(axis=0) / 255.0
    return np.concatenate([*hist, np.array(tex, dtype=np.float32), means, stds])


def load_xy():
    if CACHE.exists():
        data = np.load(CACHE, allow_pickle=True)
        return data["X"], data["y"], data["classes"].tolist()
    X, y, classes = [], [], []
    for folder in sorted(p for p in TRAIN.iterdir() if p.is_dir()):
        classes.append(folder.name)
        idx = len(classes) - 1
        for img in folder.iterdir():
            if img.suffix.lower() not in {".jpg", ".jpeg", ".png", ".bmp", ".webp"}:
                continue
            try:
                X.append(features(img))
                y.append(idx)
            except Exception:
                continue
    X = np.vstack(X)
    y = np.array(y)
    np.savez(CACHE, X=X, y=y, classes=np.array(classes, dtype=object))
    return X, y, classes


def main() -> None:
    X, y, classes = load_xy()
    Xtr, Xte, ytr, yte = train_test_split(X, y, test_size=0.2, random_state=42, stratify=y)
    scaler = StandardScaler()
    Xtr = scaler.fit_transform(Xtr)
    Xte = scaler.transform(Xte)
    clf = ExtraTreesClassifier(
        n_estimators=160,
        max_depth=14,
        min_samples_leaf=2,
        class_weight="balanced",
        random_state=42,
        n_jobs=-1,
    )
    clf.fit(Xtr, ytr)
    acc = float(clf.score(Xte, yte))
    print(classification_report(yte, clf.predict(Xte), target_names=classes))
    print("accuracy_test", acc)
    trees = []
    for est in clf.estimators_:
        t = est.tree_
        trees.append({
            "left": t.children_left.tolist(),
            "right": t.children_right.tolist(),
            "feat": t.feature.tolist(),
            "thr": t.threshold.tolist(),
            "value": t.value[:, 0, :].tolist(),
        })
    OUT.write_text(json.dumps({
        "type": "forest",
        "classes": classes,
        "accuracyTest": acc,
        "bins": BINS,
        "grid": GRID,
        "size": SIZE,
        "mean": scaler.mean_.tolist(),
        "scale": scaler.scale_.tolist(),
        "trees": trees,
        "labelsEs": LABELS_ES,
        "riesgo": RIESGO,
    }, ensure_ascii=False), encoding="utf-8")
    print("modelo ->", OUT, "bytes", OUT.stat().st_size)


if __name__ == "__main__":
    main()
