from pathlib import Path
import pandas as pd


# ==========================================================
# PATHS
# ==========================================================

BASE_DIR = Path(__file__).resolve().parent.parent

DATA_DIR = BASE_DIR / "data"
IMAGE_DIR = DATA_DIR / "images"
LABEL_DIR = DATA_DIR / "labels"

OUTPUT_FILE = DATA_DIR / "training_dataset.csv"


# ==========================================================
# DATASETS
# ==========================================================

datasets = {
    "picture_completion": {
        "excel": LABEL_DIR / "PC01.xlsx",
        "images": IMAGE_DIR / "picture_completion",
        "activity_code": "PC01"
    },

    "creative_coloring": {
        "excel": LABEL_DIR / "CC01.xlsx",
        "images": IMAGE_DIR / "creative_coloring",
        "activity_code": "CC01"
    },

    "creative_drawing": {
        "excel": LABEL_DIR / "CD01.xlsx",
        "images": IMAGE_DIR / "creative_drawing",
        "activity_code": "CD01"
    },
}


# ==========================================================
# REQUIRED TARGET COLUMNS
# ==========================================================

score_columns = [
    "originality",
    "elaboration",
    "color_exploration",
    "detail",
    "visual_organization"
]


# ==========================================================
# HELPER FUNCTION
# ==========================================================

def normalize_column(column):

    column = str(column).strip().lower()

    column = column.replace(" ", "_")
    column = column.replace("-", "_")
    column = column.replace("/", "_")

    while "__" in column:
        column = column.replace("__", "_")

    return column


# ==========================================================
# FIND TRAINING TABLE
# ==========================================================

def find_training_table(excel_file):

    excel = pd.ExcelFile(excel_file)

    preferred_sheets = [
        "Model Training",
        "Training",
        "Dataset",
        "Picture Details",
        "Creative Coloring",
        "Creative Drawing"
    ]

    sheets = []

    for preferred in preferred_sheets:
        if preferred in excel.sheet_names:
            sheets.append(preferred)

    for sheet in excel.sheet_names:
        if sheet not in sheets:
            sheets.append(sheet)

    keywords = [
        "filename",
        "student_id",
        "originality",
        "elaboration",
        "color_exploration",
        "detail",
        "visual_organization"
    ]

    for sheet in sheets:

        raw = pd.read_excel(
            excel_file,
            sheet_name=sheet,
            header=None
        )

        max_rows = min(50, len(raw))

        for row_number in range(max_rows):

            row = (
                raw.iloc[row_number]
                .fillna("")
                .astype(str)
                .str.strip()
                .str.lower()
                .tolist()
            )

            row_text = " ".join(row)

            matches = sum(
                keyword in row_text
                for keyword in keywords
            )

            if matches >= 2:

                df = pd.read_excel(
                    excel_file,
                    sheet_name=sheet,
                    header=row_number
                )

                df.columns = [
                    normalize_column(column)
                    for column in df.columns
                ]

                df = df.dropna(how="all")

                print(
                    f"Training table found in "
                    f"{excel_file.name} -> {sheet}"
                )

                return df

    return None


# ==========================================================
# FIND IMAGE FILE
# ==========================================================

def create_image_lookup(image_folder):

    extensions = [
        ".jpg",
        ".jpeg",
        ".png",
        ".webp",
        ".bmp"
    ]

    lookup = {}

    if not image_folder.exists():
        return lookup

    for file in image_folder.iterdir():

        if (
            file.is_file()
            and file.suffix.lower() in extensions
        ):
            lookup[file.stem.lower()] = file

    return lookup


# ==========================================================
# PROCESS DATA
# ==========================================================

all_records = []

print("=" * 70)
print("PREPARING COMPONENT 3 TRAINING DATA")
print("=" * 70)


for activity_name, config in datasets.items():

    print("\n")
    print("=" * 70)
    print(activity_name.upper())
    print("=" * 70)

    excel_file = config["excel"]
    image_folder = config["images"]
    activity_code = config["activity_code"]

    if not excel_file.exists():

        print(
            f"Excel file missing: {excel_file}"
        )

        continue

    if not image_folder.exists():

        print(
            f"Image folder missing: {image_folder}"
        )

        continue

    # ------------------------------------------
    # Read Excel
    # ------------------------------------------

    df = find_training_table(excel_file)

    if df is None:

        print("No training table found.")

        continue

    # ------------------------------------------
    # Find filename column
    # ------------------------------------------

    filename_candidates = [
        "filename",
        "file_name",
        "image_filename",
        "image_name",
        "image"
    ]

    filename_column = None

    for candidate in filename_candidates:

        if candidate in df.columns:
            filename_column = candidate
            break

    if filename_column is None:

        print("No filename column found.")
        continue

    # ------------------------------------------
    # Check required score columns
    # ------------------------------------------

    missing_columns = [
        column
        for column in score_columns
        if column not in df.columns
    ]

    if missing_columns:

        print(
            "Missing score columns:",
            missing_columns
        )

        continue

    # ------------------------------------------
    # Image lookup
    # ------------------------------------------

    image_lookup = create_image_lookup(
        image_folder
    )

    print(
        "Images available:",
        len(image_lookup)
    )

    # ------------------------------------------
    # Process every Excel row
    # ------------------------------------------

    for _, row in df.iterrows():

        filename = row.get(filename_column)

        if pd.isna(filename):
            continue

        filename = str(filename).strip()

        if filename == "":
            continue

        filename_stem = Path(filename).stem.lower()

        if filename_stem not in image_lookup:

            print(
                f"Image not found: {filename}"
            )

            continue

        image_file = image_lookup[
            filename_stem
        ]

        # --------------------------------------
        # Scores
        # --------------------------------------

        scores = {}

        valid_row = True

        for score_column in score_columns:

            value = pd.to_numeric(
                row.get(score_column),
                errors="coerce"
            )

            if pd.isna(value):

                valid_row = False
                break

            if value < 1 or value > 5:

                valid_row = False
                break

            scores[score_column] = float(value)

        if not valid_row:
            continue

        # --------------------------------------
        # Calculate overall creativity mean
        # --------------------------------------

        creativity_mean = sum(
            scores.values()
        ) / len(scores)

        # --------------------------------------
        # 3 creativity classes
        # --------------------------------------

        if creativity_mean < 2.5:

            creativity_level = "Developing"

        elif creativity_mean < 3.5:

            creativity_level = "Moderate"

        else:

            creativity_level = "High"

        # --------------------------------------
        # Student ID
        # --------------------------------------

        student_id = row.get(
            "student_id",
            filename.split("_")[0]
        )

        # --------------------------------------
        # Add record
        # --------------------------------------

        record = {

            "student_id":
                str(student_id),

            "activity":
                activity_name,

            "activity_code":
                activity_code,

            "filename":
                image_file.name,

            "image_path":
                str(image_file.resolve()),

            "originality":
                scores["originality"],

            "elaboration":
                scores["elaboration"],

            "color_exploration":
                scores["color_exploration"],

            "detail":
                scores["detail"],

            "visual_organization":
                scores["visual_organization"],

            "creativity_mean":
                round(creativity_mean, 2),

            "creativity_level":
                creativity_level
        }

        all_records.append(record)


# ==========================================================
# CREATE FINAL DATAFRAME
# ==========================================================

training_df = pd.DataFrame(
    all_records
)


if len(training_df) == 0:

    print("\nNo valid training records found.")

else:

    # Remove duplicate image records

    training_df = training_df.drop_duplicates(
        subset=[
            "activity",
            "filename"
        ]
    )

    # Save

    training_df.to_csv(
        OUTPUT_FILE,
        index=False
    )

    print("\n")
    print("=" * 70)
    print("TRAINING DATASET CREATED ✅")
    print("=" * 70)

    print(
        "\nTotal training images:",
        len(training_df)
    )

    print("\nImages per activity:")

    print(
        training_df[
            "activity"
        ].value_counts()
    )

    print(
        "\nCreativity level distribution:"
    )

    print(
        training_df[
            "creativity_level"
        ].value_counts()
    )

    print(
        "\nAverage creativity score:"
    )

    print(
        round(
            training_df[
                "creativity_mean"
            ].mean(),
            2
        )
    )

    print(
        "\nCSV saved to:"
    )

    print(
        OUTPUT_FILE
    )

    print(
        "\nFirst 10 training records:"
    )

    print(
        training_df.head(10).to_string(
            index=False
        )
    )


print("\nFinished.")