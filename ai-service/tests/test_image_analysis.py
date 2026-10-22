from io import BytesIO
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

import numpy as np
from PIL import Image, ImageDraw
from fastapi import HTTPException
from starlette.datastructures import UploadFile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from src.colour_analysis import analyse_colours
from src import image_creativity_api as api


def upload(image, format="PNG"):
    buffer = BytesIO()
    image.save(buffer, format=format)
    buffer.seek(0)
    return UploadFile(buffer, filename="drawing.png")


class ColourTests(unittest.TestCase):
    def test_white_offwhite_and_transparency_are_not_drawing_pixels(self):
        for mode, background in (("RGB", "white"), ("RGB", "#fffdf9"), ("RGBA", (255, 0, 0, 0))):
            image = Image.new(mode, (100, 100), background)
            result = analyse_colours(image)
            self.assertEqual(result["drawing_pixels"], 0)
            self.assertEqual(result["colours"], [])
            self.assertIsNone(result["dominant_colour"])

    def test_percentages_use_only_actual_marks(self):
        image = Image.new("RGB", (100, 100), "white")
        draw = ImageDraw.Draw(image)
        draw.rectangle((10, 10, 19, 19), fill="red")
        draw.rectangle((30, 10, 59, 19), fill="blue")
        result = analyse_colours(image)
        self.assertEqual(result["dominant_colour"], "Blue")
        self.assertEqual(result["category_count"], 2)
        self.assertEqual(result["drawing_pixels"], 400)
        self.assertEqual({c["name"]: c["percentage"] for c in result["colours"]}, {"Blue": 75, "Red": 25})

    def test_all_ten_categories_and_observed_swatches(self):
        colours = {"Red": "#ff0000", "Blue": "#0000ff", "Green": "#008000", "Yellow": "#ffff00",
                   "Orange": "#ffa500", "Purple": "#800080", "Pink": "#ffc0cb", "Brown": "#8b4513",
                   "Black": "#000000", "Gray": "#808080"}
        image = Image.new("RGB", (120, 40), "white")
        draw = ImageDraw.Draw(image)
        for index, hex_value in enumerate(colours.values()):
            draw.rectangle((5 + index * 10, 10, 9 + index * 10, 19), fill=hex_value)
        result = analyse_colours(image)
        self.assertEqual(result["category_count"], 10)
        self.assertEqual({c["name"]: c["hex"] for c in result["colours"]}, colours)
        self.assertEqual(sum(c["percentage"] for c in result["colours"]), 100)

    def test_dim_paper_with_soft_shadow_and_black_outline(self):
        pixels = np.full((60, 100, 3), 210, dtype=np.uint8)
        pixels[:, :30] = 190
        pixels[20:30, 40:50] = (255, 0, 0)
        pixels[40:45, 40:50] = 0
        result = analyse_colours(Image.fromarray(pixels))
        self.assertEqual(result["category_count"], 2)
        self.assertEqual(result["drawing_pixels"], 150)
        self.assertTrue(result["approximate"])


class PredictionTests(unittest.TestCase):
    def test_real_checkpoint_png_jpeg_and_transparent_canvas_exports(self):
        self.assertIsNotNone(api.model, api.MODEL_ERROR)
        for mode, format in (("RGB", "PNG"), ("RGB", "JPEG"), ("RGBA", "PNG")):
            image = Image.new(mode, (100, 80), "white")
            ImageDraw.Draw(image).rectangle((10, 10, 40, 40), fill="red")
            result = api.predict_image(upload(image, format))
            self.assertEqual(set(result["scores"]), set(api.EXPECTED_TARGETS))
            self.assertTrue(all(1 <= value <= 5 for value in result["scores"].values()))
            self.assertEqual(result["score_scale"], 5)
            self.assertIn("Red", [c["name"] for c in result["colour_analysis"]["colours"]])
            self.assertIsNone(result["prediction_error"])

    def test_invalid_and_empty_uploads_are_client_errors(self):
        for contents in (b"", b"this is not an image"):
            with self.assertRaises(HTTPException) as error:
                api.predict_image(UploadFile(BytesIO(contents)))
            self.assertEqual(error.exception.status_code, 400)

    def test_oversized_image_rejected(self):
        with patch.object(api, "MAX_IMAGE_BYTES", 10):
            with self.assertRaises(HTTPException) as error:
                api.predict_image(UploadFile(BytesIO(b"x" * 11)))
            self.assertEqual(error.exception.status_code, 413)

    def test_inference_failure_returns_colour_results_with_explicit_error(self):
        with patch.object(api.model, "forward", side_effect=RuntimeError("test model failure")):
            with self.assertLogs("smartplay.image", level="ERROR"):
                result = api.predict_image(upload(Image.new("RGB", (10, 10), "blue")))
        self.assertIsNone(result["scores"])
        self.assertIsNone(result["average_score"])
        self.assertIn("prediction failed", result["prediction_error"])
        self.assertEqual(result["colour_analysis"]["dominant_colour"], "Blue")

    def test_missing_checkpoint_state_is_explicit_without_substitution(self):
        with patch.object(api, "model", None), patch.object(api, "MODEL_ERROR", "Missing configured checkpoint"):
            result = api.predict_image(upload(Image.new("RGB", (10, 10), "red")))
            self.assertIsNone(result["scores"])
            self.assertEqual(result["prediction_error"], "Missing configured checkpoint")
            self.assertEqual(result["colour_analysis"]["dominant_colour"], "Red")
            self.assertEqual(api.health()["status"], "degraded")


if __name__ == "__main__":
    unittest.main()
