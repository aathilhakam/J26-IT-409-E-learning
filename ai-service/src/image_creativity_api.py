from io import BytesIO
import json
from pathlib import Path

from fastapi import FastAPI, File, HTTPException, UploadFile
from PIL import Image, UnidentifiedImageError
import numpy as np
import torch
from torch import nn



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


if not MODEL_PATH.is_file():
    raise FileNotFoundError(f"ResNet18 image model not found: {MODEL_PATH}")
if not METRICS_PATH.is_file():
    raise FileNotFoundError(f"ResNet18 evaluation metrics not found: {METRICS_PATH}")

DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")
model = ResNet18Creativity(len(EXPECTED_TARGETS)).to(DEVICE)
model.load_state_dict(
    torch.load(MODEL_PATH, map_location=DEVICE, weights_only=True),
    strict=True,
)
model.eval()
with METRICS_PATH.open(encoding="utf-8") as metrics_file:
    METRICS = json.load(metrics_file)

app = FastAPI(title="SmartPlay ResNet18 Image API", version="1.0")


@app.get("/health")
def health():
    return {
        "status": "running",
        "model": "ResNet18",
        "targets": EXPECTED_TARGETS,
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
            image = source.convert("RGB").resize(
                (IMAGE_SIZE, IMAGE_SIZE),
                Image.Resampling.BILINEAR,
            )
    except HTTPException:
        raise
    except (Image.DecompressionBombError, UnidentifiedImageError, OSError, ValueError) as exc:
        raise HTTPException(status_code=400, detail="The uploaded file is not a supported image.") from exc
    finally:
        await file.close()

    pixels = np.asarray(image, dtype=np.float32) / 255.0
    tensor = torch.from_numpy(pixels.transpose(2, 0, 1).copy())
    tensor = ((tensor - 0.5) / 0.5).unsqueeze(0).to(DEVICE)
    with torch.inference_mode():
        predictions = np.clip(
            model(tensor)[0].cpu().numpy(),
            1.0,
            5.0,
        )

    scores = {
        target: round(float(score), 3)
        for target, score in zip(EXPECTED_TARGETS, predictions, strict=True)
    }
    return {
        "model": "ResNet18 Image Model",
        "scores": scores,
        "average_score": round(sum(scores.values()) / len(scores), 3),
        "teacher_validation": "Pending",
        "message": "Experimental ResNet18 image estimates trained on teacher-scored drawings; teacher review is required.",
        "test_mae": METRICS.get("test_mae"),
        "test_r2": METRICS.get("test_r2"),
    }
