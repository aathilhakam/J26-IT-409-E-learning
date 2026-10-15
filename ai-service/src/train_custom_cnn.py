from pathlib import Path
import json
import random

import numpy as np
import pandas as pd
from PIL import Image

import torch
import torch.nn as nn
from torch.utils.data import Dataset, DataLoader
from torchvision import transforms

from sklearn.metrics import (
    mean_absolute_error,
    mean_squared_error,
    r2_score,
)


# ============================================================
# CONFIGURATION
# ============================================================

SEED = 42
IMAGE_SIZE = 224
BATCH_SIZE = 8
LEARNING_RATE = 0.001
MAX_EPOCHS = 50
PATIENCE = 10

TARGET_COLUMNS = [
    "originality",
    "elaboration",
    "color_exploration",
    "detail",
    "visual_organization",
]


# ============================================================
# PROJECT PATHS
# ============================================================

# train_custom_cnn.py is inside ai-service/src/
ROOT_DIR = Path(__file__).resolve().parents[1]

DATA_DIR = ROOT_DIR / "data"
SPLIT_DIR = DATA_DIR / "splits"

TRAIN_CSV = SPLIT_DIR / "train.csv"
VAL_CSV = SPLIT_DIR / "val.csv"
TEST_CSV = SPLIT_DIR / "test.csv"

MODEL_DIR = ROOT_DIR / "models"
RESULT_DIR = ROOT_DIR / "results"

MODEL_DIR.mkdir(parents=True, exist_ok=True)
RESULT_DIR.mkdir(parents=True, exist_ok=True)

MODEL_PATH = MODEL_DIR / "custom_cnn.pth"
RESULT_PATH = RESULT_DIR / "custom_cnn_metrics.json"
PREDICTION_PATH = RESULT_DIR / "custom_cnn_predictions.csv"


# ============================================================
# REPRODUCIBILITY
# ============================================================

random.seed(SEED)
np.random.seed(SEED)
torch.manual_seed(SEED)

if torch.cuda.is_available():
    torch.cuda.manual_seed_all(SEED)


# ============================================================
# DEVICE
# ============================================================

DEVICE = torch.device(
    "cuda" if torch.cuda.is_available() else "cpu"
)

print("=" * 70)
print("CUSTOM CNN - CREATIVITY ASSESSMENT")
print("=" * 70)

print(f"\nDevice: {DEVICE}")

if DEVICE.type == "cuda":
    print("GPU:", torch.cuda.get_device_name(0))
else:
    print("Running using CPU.")


# ============================================================
# TRANSFORMS
# ============================================================

train_transform = transforms.Compose(
    [
        transforms.Resize((IMAGE_SIZE, IMAGE_SIZE)),

        # Very small augmentation only.
        # Avoid strong color augmentation because
        # color exploration is one of our target scores.
        transforms.RandomHorizontalFlip(p=0.3),

        transforms.ToTensor(),

        transforms.Normalize(
            mean=[0.5, 0.5, 0.5],
            std=[0.5, 0.5, 0.5],
        ),
    ]
)

eval_transform = transforms.Compose(
    [
        transforms.Resize((IMAGE_SIZE, IMAGE_SIZE)),
        transforms.ToTensor(),
        transforms.Normalize(
            mean=[0.5, 0.5, 0.5],
            std=[0.5, 0.5, 0.5],
        ),
    ]
)


# ============================================================
# DATASET CLASS
# ============================================================

class CreativityDataset(Dataset):

    def __init__(self, csv_file, transform=None):

        self.csv_file = Path(csv_file)
        self.df = pd.read_csv(self.csv_file)

        self.transform = transform

        print(
            f"\nLoaded {self.csv_file.name}: "
            f"{len(self.df)} records"
        )

        # ----------------------------------------------------
        # Check required target columns
        # ----------------------------------------------------

        missing_targets = [
            column
            for column in TARGET_COLUMNS
            if column not in self.df.columns
        ]

        if missing_targets:
            raise ValueError(
                "Missing score columns in CSV: "
                + ", ".join(missing_targets)
            )

        # ----------------------------------------------------
        # Check image path
        # ----------------------------------------------------

        if "image_path" not in self.df.columns:
            raise ValueError(
                "Column 'image_path' was not found.\n"
                "Your training CSV must contain image_path."
            )

        # Convert scores to numeric
        for column in TARGET_COLUMNS:
            self.df[column] = pd.to_numeric(
                self.df[column],
                errors="coerce",
            )

        # Remove rows where target scores are missing
        original_length = len(self.df)

        self.df = self.df.dropna(
            subset=TARGET_COLUMNS
        ).reset_index(drop=True)

        removed = original_length - len(self.df)

        if removed > 0:
            print(
                f"Removed {removed} rows because "
                "creativity scores were missing."
            )

    def __len__(self):
        return len(self.df)

    def __getitem__(self, index):

        row = self.df.iloc[index]

        image_path = Path(str(row["image_path"]))

        if not image_path.exists():
            raise FileNotFoundError(
                f"Image not found:\n{image_path}"
            )

        image = Image.open(image_path).convert("RGB")

        if self.transform:
            image = self.transform(image)

        labels = row[TARGET_COLUMNS].astype(
            np.float32
        ).values

        labels = torch.tensor(
            labels,
            dtype=torch.float32,
        )

        return image, labels, index


# ============================================================
# CUSTOM CNN MODEL
# ============================================================

class CustomCreativityCNN(nn.Module):

    def __init__(self):
        super().__init__()

        self.features = nn.Sequential(

            # Block 1
            nn.Conv2d(
                3,
                32,
                kernel_size=3,
                padding=1,
            ),
            nn.BatchNorm2d(32),
            nn.ReLU(),
            nn.MaxPool2d(2),

            # Block 2
            nn.Conv2d(
                32,
                64,
                kernel_size=3,
                padding=1,
            ),
            nn.BatchNorm2d(64),
            nn.ReLU(),
            nn.MaxPool2d(2),

            # Block 3
            nn.Conv2d(
                64,
                128,
                kernel_size=3,
                padding=1,
            ),
            nn.BatchNorm2d(128),
            nn.ReLU(),
            nn.MaxPool2d(2),

            # Block 4
            nn.Conv2d(
                128,
                256,
                kernel_size=3,
                padding=1,
            ),
            nn.BatchNorm2d(256),
            nn.ReLU(),

            # Reduce feature map to 1 × 1
            nn.AdaptiveAvgPool2d((1, 1)),
        )

        self.regressor = nn.Sequential(
            nn.Flatten(),

            nn.Linear(256, 128),
            nn.ReLU(),

            nn.Dropout(0.4),

            nn.Linear(128, 64),
            nn.ReLU(),

            nn.Dropout(0.3),

            nn.Linear(
                64,
                len(TARGET_COLUMNS),
            ),
        )

    def forward(self, x):

        x = self.features(x)

        x = self.regressor(x)

        # Restrict predicted creativity scores
        # between 1 and 5
        x = 1.0 + 4.0 * torch.sigmoid(x)

        return x


# ============================================================
# LOSS FUNCTION
# ============================================================

criterion = nn.MSELoss()


# ============================================================
# TRAIN ONE EPOCH
# ============================================================

def train_one_epoch(
    model,
    dataloader,
    optimizer,
):

    model.train()

    total_loss = 0.0
    total_mae = 0.0

    for images, labels, _ in dataloader:

        images = images.to(DEVICE)
        labels = labels.to(DEVICE)

        optimizer.zero_grad()

        predictions = model(images)

        loss = criterion(
            predictions,
            labels,
        )

        loss.backward()

        optimizer.step()

        mae = torch.mean(
            torch.abs(
                predictions - labels
            )
        )

        total_loss += (
            loss.item() * images.size(0)
        )

        total_mae += (
            mae.item() * images.size(0)
        )

    dataset_size = len(
        dataloader.dataset
    )

    return (
        total_loss / dataset_size,
        total_mae / dataset_size,
    )


# ============================================================
# VALIDATION
# ============================================================

def validate(
    model,
    dataloader,
):

    model.eval()

    total_loss = 0.0
    total_mae = 0.0

    with torch.no_grad():

        for images, labels, _ in dataloader:

            images = images.to(DEVICE)
            labels = labels.to(DEVICE)

            predictions = model(images)

            loss = criterion(
                predictions,
                labels,
            )

            mae = torch.mean(
                torch.abs(
                    predictions - labels
                )
            )

            total_loss += (
                loss.item() * images.size(0)
            )

            total_mae += (
                mae.item() * images.size(0)
            )

    dataset_size = len(
        dataloader.dataset
    )

    return (
        total_loss / dataset_size,
        total_mae / dataset_size,
    )


# ============================================================
# CREATIVITY LEVEL
# ============================================================

def creativity_level(score):

    if score < 2.5:
        return "Developing"

    elif score < 3.5:
        return "Moderate"

    else:
        return "High"


# ============================================================
# TEST MODEL
# ============================================================

def test_model(
    model,
    dataloader,
):

    model.eval()

    true_values = []
    predicted_values = []
    indexes = []

    with torch.no_grad():

        for images, labels, batch_indexes in dataloader:

            images = images.to(DEVICE)

            predictions = model(images)

            true_values.append(
                labels.numpy()
            )

            predicted_values.append(
                predictions.cpu().numpy()
            )

            indexes.extend(
                batch_indexes.numpy().tolist()
            )

    true_values = np.vstack(
        true_values
    )

    predicted_values = np.vstack(
        predicted_values
    )

    return (
        true_values,
        predicted_values,
        indexes,
    )


# ============================================================
# MAIN PROGRAM
# ============================================================

def main():

    # --------------------------------------------------------
    # Load datasets
    # --------------------------------------------------------

    train_dataset = CreativityDataset(
        TRAIN_CSV,
        transform=train_transform,
    )

    val_dataset = CreativityDataset(
        VAL_CSV,
        transform=eval_transform,
    )

    test_dataset = CreativityDataset(
        TEST_CSV,
        transform=eval_transform,
    )

    print("\nDataset sizes")
    print("------------------------")
    print("Train      :", len(train_dataset))
    print("Validation :", len(val_dataset))
    print("Test       :", len(test_dataset))

    # --------------------------------------------------------
    # DataLoaders
    # --------------------------------------------------------

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

    # --------------------------------------------------------
    # Create model
    # --------------------------------------------------------

    model = CustomCreativityCNN().to(
        DEVICE
    )

    print("\nModel created:")
    print(model)

    trainable_parameters = sum(
        p.numel()
        for p in model.parameters()
        if p.requires_grad
    )

    print(
        f"\nTrainable parameters: "
        f"{trainable_parameters:,}"
    )

    # --------------------------------------------------------
    # Optimizer
    # --------------------------------------------------------

    optimizer = torch.optim.Adam(
        model.parameters(),
        lr=LEARNING_RATE,
        weight_decay=1e-4,
    )

    # --------------------------------------------------------
    # Training
    # --------------------------------------------------------

    best_val_mae = float("inf")

    patience_counter = 0

    history = []

    print("\n" + "=" * 70)
    print("TRAINING STARTED")
    print("=" * 70)

    for epoch in range(
        1,
        MAX_EPOCHS + 1,
    ):

        train_loss, train_mae = train_one_epoch(
            model,
            train_loader,
            optimizer,
        )

        val_loss, val_mae = validate(
            model,
            val_loader,
        )

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
                "train_loss": train_loss,
                "train_mae": train_mae,
                "val_loss": val_loss,
                "val_mae": val_mae,
            }
        )

        # ----------------------------------------------------
        # Save best model
        # ----------------------------------------------------

        if val_mae < best_val_mae:

            best_val_mae = val_mae

            patience_counter = 0

            torch.save(
                model.state_dict(),
                MODEL_PATH,
            )

            print(
                f"  -> Best model saved "
                f"(Val MAE: {val_mae:.4f})"
            )

        else:

            patience_counter += 1

            if patience_counter >= PATIENCE:

                print(
                    "\nEarly stopping activated."
                )

                break

    # --------------------------------------------------------
    # Load best model
    # --------------------------------------------------------

    print("\nLoading best model...")

    model.load_state_dict(
        torch.load(
            MODEL_PATH,
            map_location=DEVICE,
        )
    )

    # --------------------------------------------------------
    # Test
    # --------------------------------------------------------

    print("\n" + "=" * 70)
    print("TESTING BEST MODEL")
    print("=" * 70)

    (
        y_true,
        y_pred,
        test_indexes,
    ) = test_model(
        model,
        test_loader,
    )

    # --------------------------------------------------------
    # Overall metrics
    # --------------------------------------------------------

    overall_mae = mean_absolute_error(
        y_true.flatten(),
        y_pred.flatten(),
    )

    overall_rmse = np.sqrt(
        mean_squared_error(
            y_true.flatten(),
            y_pred.flatten(),
        )
    )

    overall_r2 = r2_score(
        y_true.flatten(),
        y_pred.flatten(),
    )

    print(
        f"\nOverall Test MAE  : "
        f"{overall_mae:.4f}"
    )

    print(
        f"Overall Test RMSE : "
        f"{overall_rmse:.4f}"
    )

    print(
        f"Overall Test R²   : "
        f"{overall_r2:.4f}"
    )

    # --------------------------------------------------------
    # Individual creativity dimension metrics
    # --------------------------------------------------------

    target_metrics = {}

    print("\nScores by creativity dimension:")
    print("-" * 70)

    for i, target in enumerate(
        TARGET_COLUMNS
    ):

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

        print(
            f"{target:22s} | "
            f"MAE: {mae:.4f} | "
            f"RMSE: {rmse:.4f} | "
            f"R²: {r2:.4f}"
        )

    # --------------------------------------------------------
    # Create prediction CSV
    # --------------------------------------------------------

    test_df = test_dataset.df.iloc[
        test_indexes
    ].copy()

    for i, target in enumerate(
        TARGET_COLUMNS
    ):

        test_df[
            f"true_{target}"
        ] = y_true[:, i]

        test_df[
            f"pred_{target}"
        ] = y_pred[:, i]

    test_df[
        "true_creativity_mean"
    ] = np.mean(
        y_true,
        axis=1,
    )

    test_df[
        "pred_creativity_mean"
    ] = np.mean(
        y_pred,
        axis=1,
    )

    test_df[
        "true_creativity_level"
    ] = test_df[
        "true_creativity_mean"
    ].apply(
        creativity_level
    )

    test_df[
        "pred_creativity_level"
    ] = test_df[
        "pred_creativity_mean"
    ].apply(
        creativity_level
    )

    test_df.to_csv(
        PREDICTION_PATH,
        index=False,
    )

    # --------------------------------------------------------
    # Three-class accuracy
    # --------------------------------------------------------

    level_accuracy = (
        test_df[
            "true_creativity_level"
        ].values
        ==
        test_df[
            "pred_creativity_level"
        ].values
    ).mean()

    print(
        f"\n3-Class Creativity Level Accuracy: "
        f"{level_accuracy * 100:.2f}%"
    )

    # --------------------------------------------------------
    # Save metrics
    # --------------------------------------------------------

    metrics = {
        "model": "Custom CNN",
        "pretrained": False,
        "train_samples": len(
            train_dataset
        ),
        "validation_samples": len(
            val_dataset
        ),
        "test_samples": len(
            test_dataset
        ),
        "best_validation_mae": float(
            best_val_mae
        ),
        "test_mae": float(
            overall_mae
        ),
        "test_rmse": float(
            overall_rmse
        ),
        "test_r2": float(
            overall_r2
        ),
        "three_class_accuracy": float(
            level_accuracy
        ),
        "target_metrics": target_metrics,
        "history": history,
    }

    with open(
        RESULT_PATH,
        "w",
        encoding="utf-8",
    ) as file:

        json.dump(
            metrics,
            file,
            indent=4,
        )

    # --------------------------------------------------------
    # Finish
    # --------------------------------------------------------

    print("\n" + "=" * 70)
    print("CUSTOM CNN TRAINING COMPLETE")
    print("=" * 70)

    print("\nFiles created:")

    print(
        "Model      :",
        MODEL_PATH,
    )

    print(
        "Metrics    :",
        RESULT_PATH,
    )

    print(
        "Predictions:",
        PREDICTION_PATH,
    )


# ============================================================
# WINDOWS SAFE ENTRY POINT
# ============================================================

if __name__ == "__main__":
    main()