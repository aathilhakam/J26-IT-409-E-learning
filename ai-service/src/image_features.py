"""Compact, deterministic visual features for creativity-image models."""

from __future__ import annotations

import numpy as np
from PIL import Image


FEATURE_VERSION = "rgb-hsv-histogram-grayscale-hog-v1"
IMAGE_SIZE = 64
HISTOGRAM_BINS = 16
HOG_CELLS = 4
HOG_BINS = 8


def extract_image_features(image: Image.Image) -> np.ndarray:
    """Extract color, luminance, and coarse shape features from an image."""
    rgb = image.convert("RGB").resize(
        (IMAGE_SIZE, IMAGE_SIZE),
        Image.Resampling.BILINEAR,
    )
    pixels = np.asarray(rgb, dtype=np.float32) / 255.0
    channels = [pixels[:, :, index] for index in range(3)]

    histograms = [
        np.histogram(channel, bins=HISTOGRAM_BINS, range=(0.0, 1.0))[0]
        for channel in channels
    ]
    hsv = np.asarray(rgb.convert("HSV"), dtype=np.float32) / 255.0
    histograms.extend(
        np.histogram(hsv[:, :, index], bins=HISTOGRAM_BINS, range=(0.0, 1.0))[0]
        for index in range(3)
    )
    histograms = [
        histogram.astype(np.float32) / float(IMAGE_SIZE * IMAGE_SIZE)
        for histogram in histograms
    ]

    gray = np.asarray(rgb.convert("L"), dtype=np.float32) / 255.0
    gray_small = np.asarray(
        Image.fromarray(np.uint8(np.round(gray * 255))).resize(
            (16, 16),
            Image.Resampling.BILINEAR,
        ),
        dtype=np.float32,
    ).reshape(-1) / 255.0

    gradient_y, gradient_x = np.gradient(gray)
    magnitude = np.hypot(gradient_x, gradient_y)
    orientation = np.mod(np.arctan2(gradient_y, gradient_x), np.pi)
    cell_size = IMAGE_SIZE // HOG_CELLS
    hog = []
    for row in range(HOG_CELLS):
        for column in range(HOG_CELLS):
            y_slice = slice(row * cell_size, (row + 1) * cell_size)
            x_slice = slice(column * cell_size, (column + 1) * cell_size)
            histogram, _ = np.histogram(
                orientation[y_slice, x_slice],
                bins=HOG_BINS,
                range=(0.0, np.pi),
                weights=magnitude[y_slice, x_slice],
            )
            norm = float(np.linalg.norm(histogram))
            if norm:
                histogram = histogram / norm
            hog.extend(histogram.astype(np.float32))

    global_statistics = []
    for channel in channels:
        global_statistics.extend(
            [
                float(np.mean(channel)),
                float(np.std(channel)),
                float(np.percentile(channel, 10)),
                float(np.percentile(channel, 50)),
                float(np.percentile(channel, 90)),
            ]
        )
    global_statistics.extend(
        [
            float(np.mean(magnitude)),
            float(np.std(magnitude)),
            float(np.mean(magnitude > 0.08)),
            float(np.std(gray)),
        ]
    )

    return np.concatenate(
        [
            *histograms,
            gray_small,
            np.asarray(hog, dtype=np.float32),
            np.asarray(global_statistics, dtype=np.float32),
        ]
    ).astype(np.float32)
