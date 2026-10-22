"""Predict Developing/Moderate/High classes for one or more voice recordings."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any, Sequence

import numpy as np
import pandas as pd
import torch

if __package__:
    from .voice_1dcnn_common import (
        BATCH_SIZE,
        CHECKPOINT_PATH,
        CLASS_NAMES,
        CustomVoice1DCNN,
        MelConfig,
        VoiceClassificationDataset,
        collate_recordings,
    )
else:
    from voice_1dcnn_common import (
        BATCH_SIZE,
        CHECKPOINT_PATH,
        CLASS_NAMES,
        CustomVoice1DCNN,
        MelConfig,
        VoiceClassificationDataset,
        collate_recordings,
    )


class Voice1DCNNPredictor:
    """Load the trained model once and reuse it for incoming recordings."""

    def __init__(
        self,
        checkpoint_path: Path = CHECKPOINT_PATH,
        device_name: str | None = None,
    ) -> None:
        if not checkpoint_path.is_file():
            raise FileNotFoundError(
                f"Trained CNN checkpoint not found: {checkpoint_path}"
            )
        checkpoint = torch.load(
            checkpoint_path, map_location="cpu", weights_only=True
        )
        self.config = MelConfig(**checkpoint["model_config"]["audio"])
        self.mean = np.asarray(
            checkpoint["normalization"]["mean"], dtype=np.float32
        )
        self.std = np.asarray(
            checkpoint["normalization"]["std"], dtype=np.float32
        )
        self.device = torch.device(
            device_name or ("cuda" if torch.cuda.is_available() else "cpu")
        )
        self.model = CustomVoice1DCNN(n_classes=3).to(self.device)
        self.model.load_state_dict(checkpoint["model_state_dict"])
        self.model.eval()

    def predict(self, audio_paths: Sequence[Path]) -> list[dict[str, Any]]:
        if not audio_paths:
            raise ValueError("Provide at least one audio recording.")
        missing = [str(path) for path in audio_paths if not path.is_file()]
        if missing:
            raise FileNotFoundError(
                "Audio recording(s) not found: " + ", ".join(missing)
            )

        rows = pd.DataFrame(
            {
                "voice_id": [path.stem for path in audio_paths],
                "student_id": ["inference"] * len(audio_paths),
                "source_class": [""] * len(audio_paths),
                "label": [0] * len(audio_paths),
                "processed_path": [path.resolve() for path in audio_paths],
            }
        )
        dataset = VoiceClassificationDataset(
            rows, self.config, mean=self.mean, std=self.std
        )
        predictions: list[dict[str, Any]] = []
        for start in range(0, len(dataset), BATCH_SIZE):
            samples = [
                dataset[index]
                for index in range(start, min(start + BATCH_SIZE, len(dataset)))
            ]
            batch = collate_recordings(samples)
            with torch.inference_mode():
                logits = self.model(
                    batch["logmel"].to(self.device),
                    batch["frame_lengths"].to(self.device),
                )
                probabilities = torch.softmax(logits, dim=1).cpu().numpy()
            for index, probability in enumerate(probabilities):
                class_index = int(probability.argmax())
                predictions.append(
                    {
                        "file": Path(batch["processed_path"][index]).name,
                        "class_index": class_index,
                        "predicted_class": CLASS_NAMES[class_index],
                        "duration_seconds": float(batch["duration_seconds"][index]),
                        "probabilities": {
                            name: float(probability[class_id])
                            for class_id, name in enumerate(CLASS_NAMES)
                        },
                    }
                )
        return predictions


def predict(
    audio_paths: list[Path],
    checkpoint_path: Path,
    device_name: str | None = None,
) -> list[dict[str, Any]]:
    return Voice1DCNNPredictor(checkpoint_path, device_name).predict(audio_paths)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("audio", nargs="+", type=Path, help="Audio file path(s)")
    parser.add_argument("--checkpoint", type=Path, default=CHECKPOINT_PATH)
    parser.add_argument("--device", choices=("cpu", "cuda"), default=None)
    parser.add_argument(
        "--output",
        type=Path,
        help="Optional CSV path for predictions; no output file is written by default.",
    )
    arguments = parser.parse_args()
    try:
        predictions = predict(
            arguments.audio, arguments.checkpoint, device_name=arguments.device
        )
        if arguments.output:
            arguments.output.parent.mkdir(parents=True, exist_ok=True)
            pd.DataFrame(predictions).to_csv(arguments.output, index=False)
        print(json.dumps(predictions, indent=2, ensure_ascii=True))
    except (FileNotFoundError, RuntimeError, ValueError, KeyError) as exc:
        print(f"Prediction failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
