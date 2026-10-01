# Spark / datasets Hospy

```bash
python spark/ingest_open_datasets.py
```

Descarga muestras públicas de Hugging Face (MedMCQA, MedQA) y escribe JSON en `src/main/resources/datasets/`.

Spark: `USE_SPARK=1 python spark/ingest_open_datasets.py` (en Windows el worker de PySpark suele fallar; el job cae a Python).

No se baja: MIMIC, DrugBank completo, ISIC 400k imágenes, DermNet, DisGeNET de pago.
