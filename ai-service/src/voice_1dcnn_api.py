"""FastAPI service for Custom Voice 1D CNN recording classification."""

from __future__ import annotations

import subprocess
import tempfile
from pathlib import Path

from fastapi import FastAPI, File, HTTPException, UploadFile
import imageio_ffmpeg
import soundfile as sf

from src.voice.predict_voice_1dcnn_classifier import Voice1DCNNPredictor
from src.voice.voice_1dcnn_common import CHECKPOINT_PATH, CLASS_NAMES


MAX_UPLOAD_BYTES = 30 * 1024 * 1024
MAX_RECORDING_SECONDS = 60
ALLOWED_EXTENSIONS = {".wav", ".ogg", ".webm", ".mp4", ".m4a", ".mp3"}

app = FastAPI(
    title="SmartPlay Custom Voice 1D CNN API",
    version="1.0",
    description=(
        "Experimental, scratch-trained voice classifier. Uploaded recordings "
        "are processed temporarily and are not retained."
    ),
)

try:
    predictor = Voice1DCNNPredictor(CHECKPOINT_PATH)
except (FileNotFoundError, KeyError, RuntimeError, ValueError) as exc:
    raise RuntimeError(
        "Could not load the Custom Voice 1D CNN checkpoint. Train the model "
        f"before starting this API: {exc}"
    ) from exc


@app.get("/health")
def health() -> dict[str, object]:
    return {
        "status": "running",
        "model": "Custom Voice 1D CNN",
        "classes": list(CLASS_NAMES),
        "pretrained": False,
        "device": str(predictor.device),
    }


@app.post("/predict/voice")
async def predict_voice(file: UploadFile = File(...)) -> dict[str, object]:
    filename = file.filename or ""
    suffix = Path(filename).suffix.casefold()
    if suffix not in ALLOWED_EXTENSIONS:
        await file.close()
        raise HTTPException(
            status_code=400,
            detail="Please upload a WAV or OGG recording.",
        )

    temporary_directory: tempfile.TemporaryDirectory[str] | None = None
    try:
        temporary_directory = tempfile.TemporaryDirectory(prefix="smartplay_voice_")
        source_path = Path(temporary_directory.name) / f"recording{suffix}"
        output_path = Path(temporary_directory.name) / "recording_16k_mono.wav"
        with source_path.open("wb") as temporary:
            total_bytes = 0
            while chunk := await file.read(1024 * 1024):
                total_bytes += len(chunk)
                if total_bytes > MAX_UPLOAD_BYTES:
                    raise HTTPException(
                        status_code=413,
                        detail="Audio file exceeds the 30 MB upload limit.",
                    )
                temporary.write(chunk)
        if total_bytes == 0:
            raise HTTPException(status_code=400, detail="The audio upload is empty.")

        try:
            conversion = subprocess.run(
                [
                    imageio_ffmpeg.get_ffmpeg_exe(),
                    "-nostdin",
                    "-hide_banner",
                    "-loglevel",
                    "error",
                    "-y",
                    "-i",
                    str(source_path),
                    "-t",
                    str(MAX_RECORDING_SECONDS + 1),
                    "-vn",
                    "-ac",
                    "1",
                    "-ar",
                    "16000",
                    "-c:a",
                    "pcm_s16le",
                    str(output_path),
                ],
                capture_output=True,
                text=True,
                check=False,
                timeout=30,
            )
        except subprocess.TimeoutExpired as exc:
            raise HTTPException(
                status_code=400,
                detail="Audio conversion timed out. Try a shorter recording.",
            ) from exc
        if conversion.returncode != 0 or not output_path.is_file():
            reason = conversion.stderr.strip()[-500:]
            raise HTTPException(
                status_code=400,
                detail=(
                    "The uploaded recording could not be decoded. "
                    + (reason or "Record the voice again and retry.")
                ),
            )
        try:
            audio_info = sf.info(output_path)
        except (OSError, RuntimeError) as exc:
            raise HTTPException(
                status_code=400,
                detail="The uploaded recording could not be converted to WAV.",
            ) from exc
        if audio_info.frames <= 0 or audio_info.samplerate <= 0:
            raise HTTPException(status_code=400, detail="The audio upload is empty.")
        if audio_info.duration > MAX_RECORDING_SECONDS:
            raise HTTPException(
                status_code=413,
                detail="Voice recordings must be 60 seconds or shorter.",
            )

        prediction = predictor.predict([output_path])[0]
        class_scores = prediction["probabilities"]
        predicted_class = str(prediction["predicted_class"])
        return {
            "model": "Custom Voice 1D CNN",
            "creativity_class": predicted_class,
            "model_score": float(class_scores[predicted_class]),
            "class_scores": class_scores,
            "teacher_validation": "Pending",
            "recording_retained": False,
            "evaluation_notice": (
                "Experimental model. It predicted only Developing on its "
                "8-recording held-out evaluation set; review with a teacher."
            ),
        }
    except HTTPException:
        raise
    except (OSError, RuntimeError, ValueError) as exc:
        raise HTTPException(
            status_code=400,
            detail=f"Voice recording could not be analyzed: {exc}",
        ) from exc
    finally:
        await file.close()
        if temporary_directory is not None:
            temporary_directory.cleanup()
