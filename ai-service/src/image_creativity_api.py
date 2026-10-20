from io import BytesIO
from pathlib import Path

from fastapi import FastAPI, File, HTTPException, UploadFile
from PIL import Image, UnidentifiedImageError
import joblib
import numpy as np

from src.image_features import FEATURE_VERSION, extract_image_features


ROOT = Path(__file__).resolve().parent.parent
MODEL_PATH = ROOT / "models" / "image_random_forest.joblib"
EXPECTED_TARGETS = [
    "originality",
    "elaboration",
    "color_exploration",
    "detail",
    "visual_organization",
]
MAX_IMAGE_BYTES = 15 * 1024 * 1024
MAX_IMAGE_PIXELS = 40_000_000


if not MODEL_PATH.is_file():
    raise FileNotFoundError(
        f"Image Random Forest model not found: {MODEL_PATH}. "
        "Run `python src/train_image_random_forest.py` from ai-service first."
    )

bundle = joblib.load(MODEL_PATH)
model = bundle.get("model")
TARGETS = bundle.get("targets")
if (
    not isinstance(TARGETS, list)
    or TARGETS != EXPECTED_TARGETS
    or bundle.get("feature_version") != FEATURE_VERSION
    or not hasattr(model, "predict")
):
    raise ValueError(f"Image Random Forest model bundle is incompatible: {MODEL_PATH}")

METRICS = bundle.get("metrics", {})
app = FastAPI(title="SmartPlay Image Random Forest API", version="1.0")


@app.get("/health")
def health():
    return {
        "status": "running",
        "model": "RandomForestRegressor",
        "targets": TARGETS,
        "test_mae": METRICS.get("test_mae"),
    }


@app.post("/predict/image")
async def predict_image(file: UploadFile = File(...)):
    try:
        contents = await file.read(MAX_IMAGE_BYTES + 1)
        if len(contents) > MAX_IMAGE_BYTES:
            raise HTTPException(status_code=413, detail="Image exceeds 15 MB.")
        if not contents:
            raise HTTPException(status_code=400, detail="Choose an image to analyze.")

        with Image.open(BytesIO(contents)) as source:
            if source.width * source.height > MAX_IMAGE_PIXELS:
                raise HTTPException(status_code=400, detail="Image dimensions are too large.")
            features = extract_image_features(source)
    except HTTPException:
        raise
    except (Image.DecompressionBombError, UnidentifiedImageError, OSError, ValueError) as exc:
        raise HTTPException(status_code=400, detail="The uploaded file is not a supported image.") from exc
    finally:
        await file.close()

    predictions = np.clip(model.predict(features.reshape(1, -1))[0], 1.0, 5.0)

    scores = {
        target: round(float(score), 3)
        for target, score in zip(TARGETS, predictions, strict=True)
    }
    return {
        "model": "Random Forest Image Regressor",
        "scores": scores,
        "average_score": round(sum(scores.values()) / len(scores), 3),
        "teacher_validation": "Pending",
        "message": "Experimental image estimates trained on teacher-scored drawings; teacher review is required.",
        "test_mae": METRICS.get("test_mae"),
        "baseline_mae": METRICS.get("train_mean_baseline_mae"),
    }
