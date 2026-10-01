"""Ingesta de datasets abiertos. Usa Spark si hay pyspark; si no, procesa en Python.
No descarga MIMIC, DrugBank, ISIC completo ni sitios con copyright.
"""
from __future__ import annotations

import json
import re
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "src" / "main" / "resources" / "datasets"
RAW = ROOT / "data" / "raw"
OUT_DIR.mkdir(parents=True, exist_ok=True)
RAW.mkdir(parents=True, exist_ok=True)

HF_ROWS = "https://datasets-server.huggingface.co/rows?dataset={ds}&config=default&split=train&offset={off}&length={n}"

ESPECIALIDAD = [
    ("cardio", "Cardiología"),
    ("heart", "Cardiología"),
    ("pulmon", "Neumología"),
    ("respir", "Neumología"),
    ("neuro", "Neurología"),
    ("derma", "Dermatología"),
    ("skin", "Dermatología"),
    ("gastro", "Gastroenterología"),
    ("endocrin", "Endocrinología"),
    ("pedia", "Pediatría"),
    ("gyne", "Ginecología"),
    ("obstet", "Ginecología"),
    ("onco", "Oncología"),
    ("nephro", "Nefrología"),
    ("urolog", "Urología"),
    ("psych", "Psiquiatría"),
    ("ent", "Otorrinolaringología"),
    ("throat", "Otorrinolaringología"),
    ("ophthal", "Oftalmología"),
]


def get_json(url: str, timeout: int = 60) -> dict | list:
    req = urllib.request.Request(url, headers={"User-Agent": "HospyDatasetBot/1.0"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def hf_sample(dataset: str, n: int = 200) -> list[dict]:
    url = HF_ROWS.format(ds=dataset, off=0, n=min(n, 100))
    data = get_json(url)
    rows = [r.get("row", r) for r in data.get("rows", [])]
    if n > 100:
        data2 = get_json(HF_ROWS.format(ds=dataset, off=100, n=min(n - 100, 100)))
        rows += [r.get("row", r) for r in data2.get("rows", [])]
    (RAW / f"{dataset.replace('/', '_')}.json").write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
    return rows


def especialidad_de(texto: str) -> str:
    t = texto.lower()
    for clave, esp in ESPECIALIDAD:
        if clave in t:
            return esp
    return "Medicina General"


def claves_de(texto: str) -> list[str]:
    pal = re.findall(r"[a-záéíóúñ]{4,}", texto.lower())
    stop = {"which", "what", "with", "that", "this", "from", "patient", "following", "most", "likely"}
    uniq = []
    for p in pal:
        if p not in stop and p not in uniq:
            uniq.append(p)
        if len(uniq) >= 6:
            break
    return uniq or ["consulta"]


def procesar_con_spark(filas: list[dict]) -> list[dict]:
    import os
    if os.environ.get("USE_SPARK") == "1":
        try:
            from pyspark.sql import SparkSession
            spark = SparkSession.builder.appName("hospy-datasets").master("local[*]").getOrCreate()
            spark.sparkContext.setLogLevel("ERROR")
            rdd = spark.sparkContext.parallelize(filas, 4)

            def to_rule(row: dict) -> dict | None:
                return _regla(row)

            out = [x for x in rdd.map(to_rule).filter(lambda x: x is not None).take(80)]
            spark.stop()
            return out
        except Exception as ex:
            print("Spark no usable, procesando en Python:", ex)
    return [_regla(r) for r in filas if _regla(r)][:80]


def _regla(row: dict) -> dict | None:
    q = str(row.get("question") or row.get("Question") or "")
    sub = str(row.get("subject") or row.get("subject_name") or "")
    if len(q) < 20:
        return None
    texto = q + " " + sub
    return {
        "claves": claves_de(texto),
        "severidad": "Amarillo",
        "esi": 3,
        "prioridad": 5,
        "especialidad": especialidad_de(texto),
        "hallazgo": f"MedMCQA/MedQA · {sub or especialidad_de(texto)}",
    }


def main() -> None:
    print("Descargando muestras abiertas de Hugging Face...")
    medmcqa = hf_sample("openlifescienceai/medmcqa", 200)
    try:
        medqa = hf_sample("GBaker/MedQA-USMLE-4-options", 100)
    except Exception as ex:
        print("MedQA no disponible:", ex)
        medqa = []

    reglas = procesar_con_spark(medmcqa + medqa)
    dest = OUT_DIR / "triage-medqa-medmcqa.json"
    dest.write_text(json.dumps(reglas, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"OK {len(reglas)} reglas -> {dest}")

    catalogo = {
        "usados": ["MedMCQA (HF, Apache 2.0)", "MedQA-USMLE muestra HF", "ESI/MTS local", "HAM10000 etiquetas", "FooDB/DrugBank reglas locales"],
        "no_descargados": [
            "MIMIC-IV-ED (PhysioNet DUA)",
            "DrugBank completo (licencia académica)",
            "ISIC/SLICE-3D imágenes (cientos de miles)",
            "HAM10000 imágenes (no comercial, Dataverse)",
            "DermNet NZ (copyright)",
            "DisGeNET completo (plan de pago)",
        ],
        "spark": "pyspark local[*] si está instalado; si no, el mismo mapa en Python",
    }
    (OUT_DIR / "catalogo-fuentes.json").write_text(json.dumps(catalogo, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
