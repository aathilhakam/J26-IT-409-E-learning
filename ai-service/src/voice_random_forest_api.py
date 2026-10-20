
# ==========================================
# SMARTPLAY COMPONENT 3
# RANDOM FOREST VOICE PREDICTION API
# ==========================================

from fastapi import FastAPI, UploadFile, File, HTTPException
from pathlib import Path
import tempfile
import os

import joblib
import numpy as np

# Reuse EXACT feature extraction from training
from src.train_voice_random_forest import extract_features

ROOT = Path(__file__).resolve().parent.parent

MODEL_PATH = ROOT / "models" / "voice_random_forest.joblib"

app = FastAPI(
    title="SmartPlay Random Forest Voice API",
    version="1.0"
)

# ==========================================
# LOAD TRAINED MODEL
# ==========================================

if not MODEL_PATH.exists():
    raise FileNotFoundError(
        f"Random Forest model not found: {MODEL_PATH}"
    )

# Load only your own trusted model file
bundle = joblib.load(MODEL_PATH)

rf_model = bundle["model"]
TARGETS = bundle["targets"]

EXPECTED_FEATURES = rf_model.n_features_in_

print("Random Forest model loaded!")
print("Model:", type(rf_model).__name__)
print("Expected features:", EXPECTED_FEATURES)
print("Output targets:", TARGETS)


# ==========================================
# HEALTH CHECK
# ==========================================

@app.get("/health")
def health():
    return {
        "status": "running",
        "model": "RandomForestRegressor",
        "expected_features": int(EXPECTED_FEATURES),
        "targets": TARGETS
    }


# ==========================================
# PREDICT VOICE SCORES
# ==========================================

@app.post("/predict/voice")
async def predict_voice(file: UploadFile = File(...)):

    filename = file.filename or ""

    if Path(filename).suffix.lower() not in {
        ".wav", ".ogg"
    }:
        raise HTTPException(
            status_code=400,
            detail="Please upload a WAV or OGG audio file."
        )

    temp_path = None

    try:
        # Store uploaded recording temporarily
        with tempfile.NamedTemporaryFile(
            suffix=Path(filename).suffix.lower(),
            delete=False
        ) as temp:

            temp_path = temp.name

            # Limit uploaded file size to 30 MB
            size = 0

            while chunk := await file.read(1024 * 1024):
                size += len(chunk)

                if size > 30 * 1024 * 1024:
                    raise HTTPException(
                        status_code=413,
                        detail="Audio file exceeds 30 MB."
                    )

                temp.write(chunk)

        # Extract original 35 training features
        features = extract_features(
            Path(temp_path)
        )

        features = np.asarray(
            features, dtype=np.float32
        ).reshape(1, -1)

        if features.shape[1] != EXPECTED_FEATURES:
            raise ValueError(
                f"Expected {EXPECTED_FEATURES} features, "
                f"got {features.shape[1]}"
            )

        # Predict six creativity scores
        predictions = rf_model.predict(features)[0]

        # Keep predictions in the 1-5 rubric range
        predictions = np.clip(predictions, 1, 5)

        scores = {
            target: round(float(score), 3)
            for target, score in zip(
                TARGETS, predictions
            )
        }

        average_score = float(np.mean(predictions))

        return {
            "model": "Random Forest Regressor",
            "scores": scores,
            "average_score": round(average_score, 3),
            "teacher_validation": "Pending",
            "message": "AI prediction requires teacher review."
        }

    except HTTPException:
        raise

    except Exception as exc:
        raise HTTPException(
            status_code=400,
            detail=f"Audio prediction failed: {exc}"
        )

    finally:
        await file.close()

        if temp_path and os.path.exists(temp_path):
            os.remove(temp_path)
