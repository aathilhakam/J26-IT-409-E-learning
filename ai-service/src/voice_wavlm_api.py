
from pathlib import Path
import os
import tempfile

import joblib
import librosa
import numpy as np
import torch

from torch import nn
from transformers import (
    AutoFeatureExtractor,
    WavLMModel
)

from fastapi import (
    FastAPI,
    File,
    HTTPException,
    UploadFile
)
from fastapi.middleware.cors import CORSMiddleware

# =====================================
# 1. PATHS
# =====================================

ROOT = Path(__file__).resolve().parents[1]
MODEL_DIR = ROOT / "models"

CHECKPOINT_PATH = (
    MODEL_DIR / "voice_wavlm_classifier.pth"
)

SCALER_PATH = (
    MODEL_DIR / "voice_wavlm_scaler.joblib"
)

if not CHECKPOINT_PATH.exists():
    raise FileNotFoundError(CHECKPOINT_PATH)

if not SCALER_PATH.exists():
    raise FileNotFoundError(SCALER_PATH)

# =====================================
# 2. LOAD SAVED CONFIGURATION
# =====================================

checkpoint = torch.load(
    CHECKPOINT_PATH,
    map_location="cpu",
    weights_only=True
)

MODEL_NAME = checkpoint["encoder_name"]
CLASSES = checkpoint["classes"]

SAMPLE_RATE = int(
    checkpoint["sample_rate"]
)

MAX_SECONDS = float(
    checkpoint["max_seconds"]
)

INPUT_DIM = int(
    checkpoint["input_dim"]
)

device = torch.device(
    "cuda" if torch.cuda.is_available()
    else "cpu"
)

print("Loading WavLM:", MODEL_NAME)
print("Device:", device)

# =====================================
# 3. LOAD PRETRAINED ENCODER
# =====================================

feature_extractor = (
    AutoFeatureExtractor.from_pretrained(
        MODEL_NAME
    )
)

wavlm_model = WavLMModel.from_pretrained(
    MODEL_NAME
).to(device)

wavlm_model.eval()

# =====================================
# 4. CREATE CLASSIFICATION HEAD
# Must match Colab architecture
# =====================================

class WavLMClassificationHead(nn.Module):

    def __init__(self, input_dim=768):
        super().__init__()

        self.network = nn.Sequential(
            nn.Linear(input_dim, 32),
            nn.ReLU(),
            nn.Dropout(0.5),
            nn.Linear(32, 3)
        )

    def forward(self, x):
        return self.network(x)


classifier = WavLMClassificationHead(
    input_dim=INPUT_DIM
).to(device)

classifier.load_state_dict(
    checkpoint["classifier_state_dict"]
)

classifier.eval()

# Load training-data scaler
# Only load joblib files that you trust.
scaler = joblib.load(SCALER_PATH)

print("WavLM classifier loaded!")

# =====================================
# 5. AUDIO PREPROCESSING
# =====================================

def predict_audio(audio_path):

    audio, sr = librosa.load(
        str(audio_path),
        sr=SAMPLE_RATE,
        mono=True,
        duration=MAX_SECONDS
    )

    if len(audio) == 0:
        raise ValueError("Empty audio recording")

    inputs = feature_extractor(
        audio,
        sampling_rate=SAMPLE_RATE,
        return_tensors="pt"
    )

    input_values = (
        inputs["input_values"].to(device)
    )

    # Extract frozen WavLM features
    with torch.inference_mode():

        outputs = wavlm_model(
            input_values=input_values
        )

        embedding = (
            outputs.last_hidden_state
            .mean(dim=1)
            .cpu()
            .numpy()
        )

    # Apply the SAME scaler used in Colab
    scaled = scaler.transform(embedding)

    tensor = torch.tensor(
        scaled,
        dtype=torch.float32,
        device=device
    )

    # Predict class
    with torch.inference_mode():

        logits = classifier(tensor)

        probabilities = torch.softmax(
            logits, dim=1
        )[0].cpu().numpy()

    predicted_index = int(
        np.argmax(probabilities)
    )

    predicted_class = CLASSES[
        predicted_index
    ]

    return {
        "creativity_class": predicted_class,
        "model_score": float(
            probabilities[predicted_index]
        ),
        "class_scores": {
            name: float(probabilities[i])
            for i, name in enumerate(CLASSES)
        },
        "model": "WavLM-Base",
        "teacher_validation": "Pending"
    }


# =====================================
# 6. FASTAPI APPLICATION
# =====================================

app = FastAPI(
    title="SmartPlay Voice Creativity AI"
)

allowed_origins = [
    origin.strip()
    for origin in os.getenv(
        "WAVLM_CORS_ORIGINS",
        "http://localhost:5173",
    ).split(",")
    if origin.strip()
]
app.add_middleware(
    CORSMiddleware,
    allow_origins=allowed_origins,
    allow_methods=["GET", "POST"],
    allow_headers=["Content-Type"],
)


@app.get("/health")
def health():
    return {
        "status": "running",
        "model": MODEL_NAME,
        "classes": CLASSES
    }


@app.post("/predict/voice")
async def predict_voice(
    file: UploadFile = File(...)
):

    filename = file.filename or ""

    # Start with PCM WAV files only
    if Path(filename).suffix.lower() != ".wav":
        raise HTTPException(
            status_code=400,
            detail="Please upload a WAV file."
        )

    contents = await file.read()

    if len(contents) > 15 * 1024 * 1024:
        raise HTTPException(
            status_code=413,
            detail="Audio file exceeds 15 MB."
        )

    temporary_path = None

    try:
        with tempfile.NamedTemporaryFile(
            suffix=".wav",
            delete=False
        ) as temp:

            temp.write(contents)
            temporary_path = temp.name

        return predict_audio(temporary_path)

    except Exception as exc:
        raise HTTPException(
            status_code=400,
            detail=f"Audio processing failed: {exc}"
        )

    finally:
        if temporary_path:
            try:
                os.unlink(temporary_path)
            except OSError:
                pass
