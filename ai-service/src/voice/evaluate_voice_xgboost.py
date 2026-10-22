"""Print saved held-out XGBoost regression results."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


from voice_common import RESULT_ROOT


METRICS_PATH = RESULT_ROOT / "metrics.json"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--metrics", type=Path, default=METRICS_PATH)
    args = parser.parse_args()
    if not args.metrics.is_file():
        raise FileNotFoundError(
            f"Metrics not found: {args.metrics}. Train the model first."
        )
    with args.metrics.open(encoding="utf-8") as stream:
        report = json.load(stream)

    print(report["model"])
    print(f"Pretrained: {report['pretrained']}")
    print(
        "Samples: "
        f"train={report['train_samples']}, "
        f"validation={report['validation_samples']}, "
        f"test={report['test_samples']}"
    )
    print(f"Features: {report['feature_count']}")
    print("\nHeld-out test regression metrics (not classification accuracy):")
    for target, result in report["targets"].items():
        test = result["test"]
        baseline = result["test_mean_baseline"]
        print(
            f"  {target}: MAE={test['mae']:.3f}, "
            f"RMSE={test['rmse']:.3f}, R2={test['r2']}; "
            f"mean-baseline MAE={baseline['mae']:.3f}"
        )
    print(f"\nCaution: {report['small_dataset_warning']}")
    print("No classification accuracy was calculated for the current class labels.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
