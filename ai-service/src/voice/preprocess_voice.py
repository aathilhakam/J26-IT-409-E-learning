"""Convert discovered voice recordings to 16 kHz mono WAV without editing originals."""

from __future__ import annotations

import argparse
import subprocess
import tempfile
from pathlib import Path

import librosa
import numpy as np
import pandas as pd
import soundfile as sf

from voice_common import (
    PROCESSED_ROOT,
    VOICE_ROOT,
    audio_root,
    column_lookup,
    discover_audio,
    load_labels,
    match_audio_by_id,
    normalized_id,
    sha256_file,
)


SAMPLE_RATE = 16_000
SHORT_AUDIO_SECONDS = 1.0
METADATA_PATH = VOICE_ROOT / "voice_audio_metadata.csv"


def load_audio(path: Path) -> tuple[np.ndarray, int, str]:
    try:
        audio, source_rate = librosa.load(
            str(path), sr=SAMPLE_RATE, mono=True, dtype=np.float32
        )
        return audio, source_rate, "librosa"
    except Exception as librosa_error:
        try:
            import imageio_ffmpeg
        except ImportError:
            raise RuntimeError(
                f"Could not decode {path.name} with librosa and imageio-ffmpeg "
                "is not installed."
            ) from librosa_error

        with tempfile.TemporaryDirectory(prefix="smartplay_voice_") as temp_dir:
            converted = Path(temp_dir) / "converted.wav"
            command = [
                imageio_ffmpeg.get_ffmpeg_exe(),
                "-nostdin",
                "-v", "error",
                "-i", str(path),
                "-ac", "1",
                "-ar", str(SAMPLE_RATE),
                "-f", "wav",
                str(converted),
            ]
            result = subprocess.run(
                command, capture_output=True, text=True, check=False
            )
            if result.returncode != 0:
                raise RuntimeError(
                    f"FFmpeg could not decode {path.name}: {result.stderr.strip()}"
                ) from librosa_error
            audio, source_rate = sf.read(
                converted, dtype="float32", always_2d=False
            )
            if audio.ndim == 2:
                audio = np.mean(audio, axis=1, dtype=np.float32)
            if source_rate != SAMPLE_RATE:
                audio = librosa.resample(
                    audio, orig_sr=source_rate, target_sr=SAMPLE_RATE
                )
            return np.asarray(audio, dtype=np.float32), SAMPLE_RATE, "ffmpeg"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--audio-dir", type=Path, default=audio_root())
    parser.add_argument("--labels", type=Path, default=None)
    parser.add_argument("--short-seconds", type=float, default=SHORT_AUDIO_SECONDS)
    args = parser.parse_args()

    source_files = discover_audio(args.audio_dir)
    if not source_files:
        raise FileNotFoundError(
            f"No supported recordings found under {args.audio_dir}. "
            f"Copy originals into {VOICE_ROOT / 'raw'}."
        )
    PROCESSED_ROOT.mkdir(parents=True, exist_ok=True)
    labels, _ = load_labels(args.labels)
    matches, match_warnings = match_audio_by_id(labels, source_files)
    matched_by_path = {
        row.audio_path: row
        for row in matches.itertuples(index=False)
        if row.audio_path
    }

    metadata: list[dict[str, object]] = []
    for source in source_files:
        source_hash = sha256_file(source)
        label_match = matched_by_path.get(str(source.resolve()))
        voice_id = label_match.voice_id if label_match else normalized_id(source.name)
        student_id = label_match.student_id if label_match else ""
        activity_id = label_match.activity_id if label_match else ""
        dest = PROCESSED_ROOT / f"{source.stem}_{source_hash[:10]}_16k_mono.wav"
        row: dict[str, object] = {
            "source_filename": source.name,
            "source_path": str(source.resolve()),
            "source_sha256": source_hash,
            "voice_id": voice_id,
            "student_id": student_id,
            "activity_id": activity_id,
            "processed_path": str(dest.resolve()),
            "sample_rate_hz": SAMPLE_RATE,
            "duration_seconds": np.nan,
            "channels": 1,
            "status": "",
            "decoder": "",
            "short_recording": False,
            "error": "",
        }
        try:
            audio, _, decoder = load_audio(source)
            if audio.size == 0:
                raise ValueError("Decoded audio is empty.")
            if not np.isfinite(audio).all():
                raise ValueError("Decoded audio contains NaN or infinite samples.")
            duration = float(len(audio) / SAMPLE_RATE)
            sf.write(dest, audio, SAMPLE_RATE, format="WAV", subtype="PCM_16")
            row.update({
                "duration_seconds": duration,
                "status": "processed",
                "decoder": decoder,
                "short_recording": duration < args.short_seconds,
            })
            if duration < args.short_seconds:
                print(
                    f"WARNING: very short recording ({duration:.2f}s): "
                    f"{source.name}"
                )
        except (OSError, RuntimeError, ValueError) as exc:
            row.update({"status": "error", "error": str(exc)})
            print(f"ERROR: {source.name}: {exc}")
        metadata.append(row)

    pd.DataFrame(metadata).to_csv(METADATA_PATH, index=False, encoding="utf-8")
    print(f"\nSource recordings: {len(source_files)}")
    print(f"Processed successfully: {sum(row['status'] == 'processed' for row in metadata)}")
    print(f"Failed or empty: {sum(row['status'] == 'error' for row in metadata)}")
    print(f"Very short (<{args.short_seconds:g}s): {sum(row['short_recording'] for row in metadata)}")
    print(f"Metadata CSV: {METADATA_PATH}")
    print("Original recordings were read only and preserved.")
    print("No noise reduction was applied.")
    if match_warnings:
        print("\nAudio/label matching notes:")
        for warning in match_warnings:
            print(f"  - {warning}")
    return 0 if any(row["status"] == "processed" for row in metadata) else 1


if __name__ == "__main__":
    raise SystemExit(main())
