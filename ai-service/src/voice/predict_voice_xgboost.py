"""Predict teacher assessment factor scores from a new voice recording."""

from __future__ import annotations

import argparse
from pathlib import Path

import joblib
import pandas as pd

from extract_voice_features import extract_features
from voice_common import MODEL_ROOT, SUPPORTED_AUDIO


MODEL_PATH = MODEL_ROOT / "voice_xgboost_regressors.joblib"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("audio", type=Path, help="WAV/MP3/M4A/OGG/FLAC recording")
    parser.add_argument("--model", type=Path, default=MODEL_PATH)
    args = parser.parse_args()
    audio_path = args.audio.expanduser().resolve()
    if not audio_path.is_file():
        raise FileNotFoundError(f"Audio file does not exist: {audio_path}")
    if audio_path.suffix.casefold() not in SUPPORTED_AUDIO:
        raise ValueError(f"Unsupported audio extension: {audio_path.suffix}")
    if not args.model.is_file():
        raise FileNotFoundError(
            f"Model bundle not found: {args.model}. Train the model first."
        )

    bundle = joblib.load(args.model)
    features = extract_features(audio_path)
    row = pd.DataFrame([features]).reindex(columns=bundle["feature_columns"])
    print(f"Recording: {audio_path.name}")
    print("Non-pretrained XGBoost estimates (1-5; teacher review required):")
    for target, model in bundle["models"].items():
        prediction = float(model.predict(row)[0])
        print(f"  {target}: {min(5.0, max(1.0, prediction)):.3f}")
    print(
        "These are experimental estimates, not validated classroom assessments. "
        "The teacher remains the authority."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
