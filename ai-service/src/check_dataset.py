from pathlib import Path
import pandas as pd


# ==========================================================
# 1. PROJECT PATHS
# ==========================================================

BASE_DIR = Path(__file__).resolve().parent.parent

IMAGES_DIR = BASE_DIR / "data" / "images"
LABELS_DIR = BASE_DIR / "data" / "labels"


# ==========================================================
# 2. DATASET CONFIGURATION
# ==========================================================

datasets = {
    "Picture Completion": {
        "excel": LABELS_DIR / "PC01.xlsx",
        "images": IMAGES_DIR / "picture_completion",
    },

    "Creative Coloring": {
        "excel": LABELS_DIR / "CC01.xlsx",
        "images": IMAGES_DIR / "creative_coloring",
    },

    "Creative Drawing": {
        "excel": LABELS_DIR / "CD01.xlsx",
        "images": IMAGES_DIR / "creative_drawing",
    },
}


# ==========================================================
# 3. CREATIVITY SCORE COLUMNS
# ==========================================================

score_columns = [
    "originality",
    "elaboration",
    "color_exploration",
    "detail",
    "visual_organization",
]


# ==========================================================
# 4. POSSIBLE FILENAME COLUMN NAMES
# ==========================================================

possible_filename_columns = [
    "filename",
    "file_name",
    "image_filename",
    "image_name",
    "image",
    "file",
]


# ==========================================================
# 5. HELPER FUNCTION
# ==========================================================

def normalize_column_name(column):
    """
    Convert column names to lowercase snake_case.
    Example:
    Visual Organization -> visual_organization
    """

    column = str(column).strip().lower()

    column = column.replace(" ", "_")
    column = column.replace("-", "_")
    column = column.replace("/", "_")

    while "__" in column:
        column = column.replace("__", "_")

    return column


# ==========================================================
# 6. FIND TRAINING TABLE
# ==========================================================

def find_training_table(excel_file):

    excel = pd.ExcelFile(excel_file)

    print("\nSheets found:")

    for sheet in excel.sheet_names:
        print(" -", sheet)

    # ------------------------------------------------------
    # First preference: Model Training sheet
    # ------------------------------------------------------

    preferred_sheets = [
        "Model Training",
        "Training",
        "Dataset",
        "Picture Details",
    ]

    ordered_sheets = []

    for preferred in preferred_sheets:
        if preferred in excel.sheet_names:
            ordered_sheets.append(preferred)

    for sheet in excel.sheet_names:
        if sheet not in ordered_sheets:
            ordered_sheets.append(sheet)

    # ------------------------------------------------------
    # Search each sheet
    # ------------------------------------------------------

    search_words = [
        "filename",
        "student_id",
        "originality",
        "elaboration",
        "color_exploration",
        "detail",
        "visual_organization",
    ]

    for sheet in ordered_sheets:

        print(f"\nChecking sheet: {sheet}")

        raw_df = pd.read_excel(
            excel_file,
            sheet_name=sheet,
            header=None
        )

        # Search first 40 rows
        max_rows = min(40, len(raw_df))

        for row_number in range(max_rows):

            # IMPORTANT FIX FOR YOUR ERROR
            row = (
                raw_df.iloc[row_number]
                .fillna("")
                .astype(str)
                .str.strip()
                .str.lower()
                .tolist()
            )

            row_text = " ".join(row)

            match_count = 0

            for word in search_words:
                if word in row_text:
                    match_count += 1

            # If several expected column names exist,
            # this is probably the header row.
            if match_count >= 2:

                print(
                    f"Possible training header found "
                    f"at Excel row {row_number + 1} ✅"
                )

                df = pd.read_excel(
                    excel_file,
                    sheet_name=sheet,
                    header=row_number
                )

                # Normalize columns
                df.columns = [
                    normalize_column_name(col)
                    for col in df.columns
                ]

                # Remove completely empty rows
                df = df.dropna(how="all")

                return df, sheet

    return None, None


# ==========================================================
# 7. CHECK EACH DATASET
# ==========================================================

print("\n")
print("=" * 70)
print("COMPONENT 3 DATASET CHECKER")
print("=" * 70)


for dataset_name, paths in datasets.items():

    excel_file = paths["excel"]
    image_folder = paths["images"]

    print("\n\n")
    print("=" * 70)
    print(f"CHECKING: {dataset_name}")
    print("=" * 70)

    print("\nExcel file:")
    print(excel_file)

    print("\nImage folder:")
    print(image_folder)

    # ------------------------------------------------------
    # Check Excel
    # ------------------------------------------------------

    if not excel_file.exists():

        print("\n❌ Excel file does not exist.")
        continue

    print("\n✅ Excel file found.")

    # ------------------------------------------------------
    # Check image folder
    # ------------------------------------------------------

    if not image_folder.exists():

        print("\n❌ Image folder does not exist.")
        continue

    print("✅ Image folder found.")

    # ------------------------------------------------------
    # Get images
    # ------------------------------------------------------

    allowed_extensions = [
        ".jpg",
        ".jpeg",
        ".png",
        ".webp",
        ".bmp",
    ]

    image_files = []

    for file in image_folder.iterdir():

        if (
            file.is_file()
            and file.suffix.lower() in allowed_extensions
        ):
            image_files.append(file)

    print(f"\nImages found: {len(image_files)}")

    # ------------------------------------------------------
    # Find training table
    # ------------------------------------------------------

    try:

        df, training_sheet = find_training_table(excel_file)

    except Exception as error:

        print("\n❌ Error reading Excel:")
        print(error)

        continue

    if df is None:

        print("\n❌ No training table automatically detected.")
        continue

    print("\n")
    print("-" * 70)
    print("TRAINING TABLE FOUND ✅")
    print("-" * 70)

    print("\nSheet:")
    print(training_sheet)

    print("\nColumns:")

    for column in df.columns:
        print(" -", column)

    print("\nNumber of Excel rows:")
    print(len(df))

    print("\nFirst 5 records:")

    print(
        df.head(5).to_string(
            index=False
        )
    )

    # ======================================================
    # 8. FIND FILENAME COLUMN
    # ======================================================

    filename_column = None

    for possible_column in possible_filename_columns:

        if possible_column in df.columns:

            filename_column = possible_column
            break

    if filename_column is None:

        print("\n❌ No filename column found.")

        print(
            "\nYour Excel training sheet should contain "
            "a column named:"
        )

        print("filename")

    else:

        print(
            f"\nFilename column found: "
            f"{filename_column} ✅"
        )

        # --------------------------------------------------
        # Excel filenames
        # --------------------------------------------------

        excel_filenames = (
            df[filename_column]
            .dropna()
            .astype(str)
            .str.strip()
            .tolist()
        )

        # --------------------------------------------------
        # Compare using filename STEM
        #
        # Example:
        # S001_PC01
        # S001_PC01.jpg
        #
        # are treated as the same image.
        # --------------------------------------------------

        excel_stems = {
            Path(name).stem.lower()
            for name in excel_filenames
            if name
        }

        folder_stems = {
            file.stem.lower()
            for file in image_files
        }

        # Excel rows without image
        missing_images = excel_stems - folder_stems

        # Images without Excel row
        unlabelled_images = folder_stems - excel_stems

        print("\n")
        print("-" * 70)
        print("IMAGE / EXCEL MATCHING")
        print("-" * 70)

        print(
            f"\nExcel image records: "
            f"{len(excel_stems)}"
        )

        print(
            f"Images in folder: "
            f"{len(folder_stems)}"
        )

        # --------------------------------------------------
        # Missing images
        # --------------------------------------------------

        print(
            "\nExcel rows with missing image files:"
        )

        if missing_images:

            for image in sorted(missing_images):
                print(" ❌", image)

        else:

            print("None ✅")

        # --------------------------------------------------
        # Unlabelled images
        # --------------------------------------------------

        print(
            "\nImages in folder but missing from Excel:"
        )

        if unlabelled_images:

            for image in sorted(unlabelled_images):
                print(" ⚠️", image)

        else:

            print("None ✅")

    # ======================================================
    # 9. CHECK SCORE COLUMNS
    # ======================================================

    print("\n")
    print("-" * 70)
    print("CHECKING CREATIVITY SCORE COLUMNS")
    print("-" * 70)

    missing_score_columns = []

    for column in score_columns:

        if column in df.columns:

            print(f"{column}: OK ✅")

        else:

            print(f"{column}: MISSING ❌")

            missing_score_columns.append(column)

    # ======================================================
    # 10. CHECK SCORE VALUES
    # ======================================================

    if len(missing_score_columns) == 0:

        print("\n")
        print("-" * 70)
        print("CHECKING SCORE VALUES")
        print("-" * 70)

        for column in score_columns:

            numeric_scores = pd.to_numeric(
                df[column],
                errors="coerce"
            )

            valid_scores = numeric_scores.dropna()

            print(f"\n{column}")

            if len(valid_scores) == 0:

                print(" No numeric scores found.")

            else:

                print(
                    " Records:",
                    len(valid_scores)
                )

                print(
                    " Minimum:",
                    valid_scores.min()
                )

                print(
                    " Maximum:",
                    valid_scores.max()
                )

                print(
                    " Mean:",
                    round(
                        valid_scores.mean(),
                        2
                    )
                )

                # Check if score outside 1-5
                invalid_scores = valid_scores[
                    (valid_scores < 1)
                    |
                    (valid_scores > 5)
                ]

                if len(invalid_scores) > 0:

                    print(
                        " ⚠️ Scores outside "
                        "the 1-5 range found."
                    )

                else:

                    print(
                        " Score range 1-5: OK ✅"
                    )

    print("\n")
    print("=" * 70)
    print(
        f"{dataset_name} CHECK COMPLETE"
    )
    print("=" * 70)


print("\n\n")
print("=" * 70)
print("ALL DATASET CHECKING COMPLETED ✅")
print("=" * 70)