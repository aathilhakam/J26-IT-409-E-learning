from io import BytesIO
import json
import logging
from pathlib import Path

from fastapi import FastAPI, File, HTTPException, UploadFile
from PIL import Image, ImageOps, UnidentifiedImageError
import numpy as np
import torch
from torch import nn
from .colour_analysis import analyse_colours



ROOT = Path(__file__).resolve().parent.parent
MODEL_PATH = ROOT / "models" / "resnet18_scratch.pth"
METRICS_PATH = ROOT / "results" / "resnet18_metrics.json"
EXPECTED_TARGETS = [
    "originality",
    "elaboration",
    "color_exploration",
    "detail",
    "visual_organization",
]
IMAGE_SIZE = 224
MAX_IMAGE_BYTES = 15 * 1024 * 1024
MAX_IMAGE_PIXELS = 40_000_000


class BasicBlock(nn.Module):
    expansion = 1

    def __init__(self, in_channels, out_channels, stride=1, downsample=None):
        super().__init__()
        self.conv1 = nn.Conv2d(
            in_channels, out_channels, kernel_size=3, stride=stride, padding=1, bias=False
        )
        self.bn1 = nn.BatchNorm2d(out_channels)
        self.relu = nn.ReLU(inplace=True)
        self.conv2 = nn.Conv2d(
            out_channels, out_channels, kernel_size=3, stride=1, padding=1, bias=False
        )
        self.bn2 = nn.BatchNorm2d(out_channels)
        self.downsample = downsample
        self.stride = stride

    def forward(self, inputs):
        identity = inputs
        outputs = self.relu(self.bn1(self.conv1(inputs)))
        outputs = self.bn2(self.conv2(outputs))
        if self.downsample is not None:
            identity = self.downsample(inputs)
        return self.relu(outputs + identity)


class ResNet18Creativity(nn.Module):
    def __init__(self, number_of_outputs):
        super().__init__()
        self.in_channels = 64
        self.conv1 = nn.Conv2d(
            3, 64, kernel_size=7, stride=2, padding=3, bias=False
        )
        self.bn1 = nn.BatchNorm2d(64)
        self.relu = nn.ReLU(inplace=True)
        self.maxpool = nn.MaxPool2d(kernel_size=3, stride=2, padding=1)
        self.layer1 = self._make_layer(64, blocks=2)
        self.layer2 = self._make_layer(128, blocks=2, stride=2)
        self.layer3 = self._make_layer(256, blocks=2, stride=2)
        self.layer4 = self._make_layer(512, blocks=2, stride=2)
        self.avgpool = nn.AdaptiveAvgPool2d((1, 1))
        self.fc = nn.Linear(512, number_of_outputs)

    def _make_layer(self, out_channels, blocks, stride=1):
        downsample = None
        if stride != 1 or self.in_channels != out_channels:
            downsample = nn.Sequential(
                nn.Conv2d(
                    self.in_channels,
                    out_channels,
                    kernel_size=1,
                    stride=stride,
                    bias=False,
                ),
                nn.BatchNorm2d(out_channels),
            )
        layers = [
            BasicBlock(self.in_channels, out_channels, stride, downsample)
        ]
        self.in_channels = out_channels
        layers.extend(
            BasicBlock(self.in_channels, out_channels)
            for _ in range(1, blocks)
        )
        return nn.Sequential(*layers)

    def forward(self, inputs):
        outputs = self.maxpool(self.relu(self.bn1(self.conv1(inputs))))
        outputs = self.layer1(outputs)
        outputs = self.layer2(outputs)
        outputs = self.layer3(outputs)
        outputs = self.layer4(outputs)
        outputs = self.avgpool(outputs)
        return self.fc(torch.flatten(outputs, 1))


logger = logging.getLogger("smartplay.image")
DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")
model = None
MODEL_ERROR = None
METRICS = {}
try:
    if not MODEL_PATH.is_file():
        raise FileNotFoundError(f"ResNet18 image model not found: {MODEL_PATH}")
    loaded_model = ResNet18Creativity(len(EXPECTED_TARGETS)).to(DEVICE)
    loaded_model.load_state_dict(torch.load(MODEL_PATH, map_location=DEVICE, weights_only=True), strict=True)
    loaded_model.eval()
    model = loaded_model
except Exception:
    logger.exception("Cannot load configured ResNet18 checkpoint: %s", MODEL_PATH)
    MODEL_ERROR = "Configured ResNet18 checkpoint could not be loaded. Check the image service startup log and ai-service/models/resnet18_scratch.pth; restart after fixing it."
try:
    with METRICS_PATH.open(encoding="utf-8") as metrics_file:
        METRICS = json.load(metrics_file)
except (OSError, ValueError):
    logger.exception("Evaluation report unavailable: %s", METRICS_PATH)

app = FastAPI(title="SmartPlay ResNet18 Image API", version="1.0")


@app.get("/health")
def health():
    return {
        "status": "running" if model is not None else "degraded",
        "prediction_error": MODEL_ERROR,
        "colour_analysis_available": True,
        "model": "ResNet18",
        "targets": EXPECTED_TARGETS,
        "test_mae": METRICS.get("test_mae"),
    }


@app.post("/predict/image")
def predict_image(file: UploadFile = File(...)):
    try:
        contents = file.file.read(MAX_IMAGE_BYTES + 1)
        if len(contents) > MAX_IMAGE_BYTES:
            raise HTTPException(status_code=413, detail="Image exceeds 15 MB.")
        if not contents:
            raise HTTPException(status_code=400, detail="Choose an image to analyze.")

        with Image.open(BytesIO(contents)) as source:
            if source.width * source.height > MAX_IMAGE_PIXELS:
                raise HTTPException(status_code=400, detail="Image dimensions are too large.")
            artwork = ImageOps.exif_transpose(source).convert("RGBA")
            # Transparent drawing exports should have white paper for inference.
            white_paper = Image.new("RGBA", artwork.size, "white")
            image = Image.alpha_composite(white_paper, artwork).convert("RGB").resize(
                (IMAGE_SIZE, IMAGE_SIZE),
                Image.Resampling.BILINEAR,
            )
    except HTTPException:
        raise
    except (Image.DecompressionBombError, UnidentifiedImageError, OSError, ValueError) as exc:
        raise HTTPException(status_code=400, detail="The uploaded file is not a supported image.") from exc
    finally:
        file.file.close()

    colour_analysis = analyse_colours(artwork)
    prediction_error = MODEL_ERROR
    scores = None
    pixels = np.asarray(image, dtype=np.float32) / 255.0
    tensor = torch.from_numpy(pixels.transpose(2, 0, 1).copy())
    tensor = ((tensor - 0.5) / 0.5).unsqueeze(0)
    if model is not None:
        try:
            tensor = tensor.to(DEVICE)
            with torch.inference_mode():
                predictions = np.clip(model(tensor)[0].cpu().numpy(), 1.0, 5.0)
            if predictions.shape != (len(EXPECTED_TARGETS),) or not np.isfinite(predictions).all():
                raise ValueError("Model returned non-finite scores or an unexpected output shape")
            scores = {target: round(float(score), 3)
                      for target, score in zip(EXPECTED_TARGETS, predictions, strict=True)}
        except Exception:
            logger.exception("ResNet18 prediction failed (input shape %s)", tuple(tensor.shape))
            prediction_error = "ResNet18 prediction failed. Colour analysis completed; inspect the image service traceback for the cause."
    return {
        "model": "ResNet18 Image Model",
        "scores": scores,
        "average_score": round(sum(scores.values()) / len(scores), 3) if scores else None,
        "score_scale": 5,
        "prediction_error": prediction_error,
        "colour_analysis": colour_analysis,
        "teacher_validation": "Pending",
        "message": "Experimental ResNet18 image estimates trained on teacher-scored drawings; teacher review is required.",
        "test_mae": METRICS.get("test_mae"),
        "test_r2": METRICS.get("test_r2"),
    }
