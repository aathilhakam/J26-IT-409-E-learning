import os
import json
from pathlib import Path

import numpy as np
import pandas as pd
from PIL import Image

import torch
import torch.nn as nn
from torch.utils.data import Dataset, DataLoader
from torchvision import transforms
from torchvision.models import resnet18

from sklearn.metrics import (
    mean_absolute_error,
    mean_squared_error,
    r2_score,
    accuracy_score,
    classification_report,
)




BASE_DIR = Path(__file__).resolve().parent.parent

TRAIN_CSV = BASE_DIR / "data" / "splits" / "train.csv"
VAL_CSV = BASE_DIR / "data" / "splits" / "val.csv"
TEST_CSV = BASE_DIR / "data" / "splits" / "test.csv"

MODEL_DIR = BASE_DIR / "models"
RESULT_DIR = BASE_DIR / "results"

MODEL_DIR.mkdir(exist_ok=True)
RESULT_DIR.mkdir(exist_ok=True)

MODEL_PATH = MODEL_DIR / "resnet18_scratch.pth"
METRICS_PATH = RESULT_DIR / "resnet18_metrics.json"
PREDICTIONS_PATH = RESULT_DIR / "resnet18_predictions.csv"

TARGET_COLUMNS = [
    "originality",
    "elaboration",
    "color_exploration",
    "detail",
    "visual_organization",
]

IMAGE_SIZE = 224
BATCH_SIZE = 8
LEARNING_RATE = 0.0001
MAX_EPOCHS = 50
PATIENCE = 10

DEVICE = torch.device(
    "cuda" if torch.cuda.is_available() else "cpu"
)

print("Device:", DEVICE)




class CreativityDataset(Dataset):

    def __init__(self, csv_file, transform=None):

        self.df = pd.read_csv(csv_file)
        self.transform = transform

        print(f"\nLoaded: {csv_file}")
        print("Records:", len(self.df))

        # Find image path column automatically
        possible_path_columns = [
            "image_path",
            "filepath",
            "file_path",
            "path",
        ]

        self.path_column = None

        for column in possible_path_columns:
            if column in self.df.columns:
                self.path_column = column
                break

        if self.path_column is None:
            raise ValueError(
                "Could not find image path column.\n"
                f"Available columns: {list(self.df.columns)}"
            )

        # Check score columns
        missing = [
            column
            for column in TARGET_COLUMNS
            if column not in self.df.columns
        ]

        if missing:
            raise ValueError(
                f"Missing target columns: {missing}"
            )

    def __len__(self):
        return len(self.df)

    def __getitem__(self, index):

        row = self.df.iloc[index]

        image_path = str(row[self.path_column])

        image = Image.open(image_path).convert("RGB")

        if self.transform:
            image = self.transform(image)

        scores = row[TARGET_COLUMNS].values.astype(
            np.float32
        )

        scores = torch.tensor(
            scores,
            dtype=torch.float32
        )

        return image, scores




train_transform = transforms.Compose(
    [
        transforms.Resize(
            (IMAGE_SIZE, IMAGE_SIZE)
        ),

        transforms.ToTensor(),

        transforms.Normalize(
            mean=[0.5, 0.5, 0.5],
            std=[0.5, 0.5, 0.5],
        ),
    ]
)

test_transform = transforms.Compose(
    [
        transforms.Resize(
            (IMAGE_SIZE, IMAGE_SIZE)
        ),

        transforms.ToTensor(),

        transforms.Normalize(
            mean=[0.5, 0.5, 0.5],
            std=[0.5, 0.5, 0.5],
        ),
    ]
)


# ============================================================
# LOAD DATA
# ============================================================

train_dataset = CreativityDataset(
    TRAIN_CSV,
    transform=train_transform,
)

val_dataset = CreativityDataset(
    VAL_CSV,
    transform=test_transform,
)

test_dataset = CreativityDataset(
    TEST_CSV,
    transform=test_transform,
)

train_loader = DataLoader(
    train_dataset,
    batch_size=BATCH_SIZE,
    shuffle=True,
    num_workers=0,
)

val_loader = DataLoader(
    val_dataset,
    batch_size=BATCH_SIZE,
    shuffle=False,
    num_workers=0,
)

test_loader = DataLoader(
    test_dataset,
    batch_size=BATCH_SIZE,
    shuffle=False,
    num_workers=0,
)




model = resnet18(weights=None)



number_features = model.fc.in_features

model.fc = nn.Linear(
    number_features,
    len(TARGET_COLUMNS),
)

model = model.to(DEVICE)

print("\n========================================")
print("RESNET18 MODEL")
print("========================================")
print(model)

trainable_parameters = sum(
    p.numel()
    for p in model.parameters()
    if p.requires_grad
)

print(
    "\nTrainable parameters:",
    f"{trainable_parameters:,}",
)


# ============================================================
# LOSS AND OPTIMIZER
# ============================================================

criterion = nn.MSELoss()

optimizer = torch.optim.Adam(
    model.parameters(),
    lr=LEARNING_RATE,
)




def evaluate(loader):

    model.eval()

    all_predictions = []
    all_targets = []

    total_loss = 0.0

    with torch.no_grad():

        for images, targets in loader:

            images = images.to(DEVICE)
            targets = targets.to(DEVICE)

            predictions = model(images)

            loss = criterion(
                predictions,
                targets,
            )

            total_loss += (
                loss.item() * images.size(0)
            )

            all_predictions.append(
                predictions.cpu().numpy()
            )

            all_targets.append(
                targets.cpu().numpy()
            )

    predictions = np.concatenate(
        all_predictions,
        axis=0,
    )

    targets = np.concatenate(
        all_targets,
        axis=0,
    )

    loss = total_loss / len(loader.dataset)

    mae = mean_absolute_error(
        targets,
        predictions,
    )

    return (
        loss,
        mae,
        targets,
        predictions,
    )


# ============================================================
# TRAIN
# ============================================================

history = []

best_validation_mae = float("inf")
epochs_without_improvement = 0

print("\n========================================")
print("TRAINING STARTED")
print("========================================")


for epoch in range(1, MAX_EPOCHS + 1):

    model.train()

    train_loss_total = 0.0

    train_predictions = []
    train_targets = []

    for images, targets in train_loader:

        images = images.to(DEVICE)
        targets = targets.to(DEVICE)

        optimizer.zero_grad()

        predictions = model(images)

        loss = criterion(
            predictions,
            targets,
        )

        loss.backward()

        optimizer.step()

        train_loss_total += (
            loss.item() * images.size(0)
        )

        train_predictions.append(
            predictions.detach().cpu().numpy()
        )

        train_targets.append(
            targets.detach().cpu().numpy()
        )

    train_loss = (
        train_loss_total /
        len(train_dataset)
    )

    train_predictions_np = np.concatenate(
        train_predictions,
        axis=0,
    )

    train_targets_np = np.concatenate(
        train_targets,
        axis=0,
    )

    train_mae = mean_absolute_error(
        train_targets_np,
        train_predictions_np,
    )

    (
        val_loss,
        val_mae,
        _,
        _,
    ) = evaluate(val_loader)

    print(
        f"Epoch {epoch:02d}/{MAX_EPOCHS} | "
        f"Train Loss: {train_loss:.4f} | "
        f"Train MAE: {train_mae:.4f} | "
        f"Val Loss: {val_loss:.4f} | "
        f"Val MAE: {val_mae:.4f}"
    )

    history.append(
        {
            "epoch": epoch,
            "train_loss": float(train_loss),
            "train_mae": float(train_mae),
            "val_loss": float(val_loss),
            "val_mae": float(val_mae),
        }
    )

    # Save best model
    if val_mae < best_validation_mae:

        best_validation_mae = val_mae

        torch.save(
            model.state_dict(),
            MODEL_PATH,
        )

        epochs_without_improvement = 0

        print(
            f"  -> Best model saved "
            f"(Val MAE: {val_mae:.4f})"
        )

    else:

        epochs_without_improvement += 1

    # Early stopping
    if epochs_without_improvement >= PATIENCE:

        print(
            "\nEarly stopping activated."
        )

        break



model.load_state_dict(
    torch.load(
        MODEL_PATH,
        map_location=DEVICE,
    )
)

model.eval()




(
    test_loss,
    test_mae,
    y_true,
    y_pred,
) = evaluate(test_loader)


test_rmse = np.sqrt(
    mean_squared_error(
        y_true,
        y_pred,
    )
)

test_r2 = r2_score(
    y_true,
    y_pred,
)



target_metrics = {}

for i, target in enumerate(TARGET_COLUMNS):

    mae = mean_absolute_error(
        y_true[:, i],
        y_pred[:, i],
    )

    rmse = np.sqrt(
        mean_squared_error(
            y_true[:, i],
            y_pred[:, i],
        )
    )

    r2 = r2_score(
        y_true[:, i],
        y_pred[:, i],
    )

    target_metrics[target] = {
        "mae": float(mae),
        "rmse": float(rmse),
        "r2": float(r2),
    }


# ============================================================
# 3 CLASS CREATIVITY LEVEL
# ============================================================

def creativity_level(score):

    if score < 2.5:
        return "Developing"

    elif score < 3.5:
        return "Moderate"

    else:
        return "High"


true_mean = y_true.mean(axis=1)
predicted_mean = y_pred.mean(axis=1)

true_classes = [
    creativity_level(score)
    for score in true_mean
]

predicted_classes = [
    creativity_level(score)
    for score in predicted_mean
]

three_class_accuracy = accuracy_score(
    true_classes,
    predicted_classes,
)


print("\n========================================")
print("TEST RESULTS")
print("========================================")

print(
    f"Test MAE: {test_mae:.4f}"
)

print(
    f"Test RMSE: {test_rmse:.4f}"
)

print(
    f"Test R2: {test_r2:.4f}"
)

print(
    f"3-Class Creativity Accuracy: "
    f"{three_class_accuracy * 100:.2f}%"
)


print("\nPer-target results:")

for target, values in target_metrics.items():

    print(
        f"\n{target}"
    )

    print(
        f"  MAE:  {values['mae']:.4f}"
    )

    print(
        f"  RMSE: {values['rmse']:.4f}"
    )

    print(
        f"  R2:   {values['r2']:.4f}"
    )


print("\nClassification report:")

print(
    classification_report(
        true_classes,
        predicted_classes,
        zero_division=0,
    )
)


# ============================================================
# SAVE PREDICTIONS
# ============================================================

test_df = pd.read_csv(TEST_CSV)

for i, target in enumerate(TARGET_COLUMNS):

    test_df[
        f"actual_{target}"
    ] = y_true[:, i]

    test_df[
        f"predicted_{target}"
    ] = y_pred[:, i]


test_df[
    "actual_creativity_mean"
] = true_mean

test_df[
    "predicted_creativity_mean"
] = predicted_mean

test_df[
    "actual_creativity_level"
] = true_classes

test_df[
    "predicted_creativity_level"
] = predicted_classes

test_df.to_csv(
    PREDICTIONS_PATH,
    index=False,
)


# ============================================================
# SAVE METRICS
# ============================================================

results = {
    "model": "ResNet18",
    "pretrained": False,
    "train_samples": len(train_dataset),
    "validation_samples": len(val_dataset),
    "test_samples": len(test_dataset),
    "best_validation_mae": float(
        best_validation_mae
    ),
    "test_mae": float(test_mae),
    "test_rmse": float(test_rmse),
    "test_r2": float(test_r2),
    "three_class_accuracy": float(
        three_class_accuracy
    ),
    "target_metrics": target_metrics,
    "history": history,
}


with open(
    METRICS_PATH,
    "w",
    encoding="utf-8",
) as file:

    json.dump(
        results,
        file,
        indent=4,
    )


print("\n========================================")
print("RESNET18 TRAINING COMPLETE")
print("========================================")

print(
    "Model:",
    MODEL_PATH,
)

print(
    "Metrics:",
    METRICS_PATH,
)

print(
    "Predictions:",
    PREDICTIONS_PATH,
)