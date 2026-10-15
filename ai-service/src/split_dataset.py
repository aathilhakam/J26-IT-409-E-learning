from pathlib import Path
import pandas as pd
from sklearn.model_selection import train_test_split


# ==========================================================
# PATHS
# ==========================================================

BASE_DIR = Path(__file__).resolve().parent.parent

DATA_FILE = BASE_DIR / "data" / "training_dataset.csv"
SPLIT_DIR = BASE_DIR / "data" / "splits"

SPLIT_DIR.mkdir(parents=True, exist_ok=True)

TRAIN_FILE = SPLIT_DIR / "train.csv"
VAL_FILE = SPLIT_DIR / "val.csv"
TEST_FILE = SPLIT_DIR / "test.csv"


# ==========================================================
# SETTINGS
# ==========================================================

RANDOM_STATE = 42

TRAIN_RATIO = 0.70
VAL_RATIO = 0.15
TEST_RATIO = 0.15


# ==========================================================
# LOAD DATA
# ==========================================================

print("=" * 70)
print("COMPONENT 3 - DATASET SPLITTING")
print("=" * 70)

df = pd.read_csv(DATA_FILE)

print("\nTotal records:", len(df))

print("\nColumns:")
print(df.columns.tolist())


# ==========================================================
# CHECK STUDENT ID
# ==========================================================

if "student_id" not in df.columns:
    raise ValueError(
        "student_id column is missing from training_dataset.csv"
    )


# Clean student IDs

df["student_id"] = (
    df["student_id"]
    .astype(str)
    .str.strip()
)


# ==========================================================
# GET UNIQUE STUDENTS
# ==========================================================

students = df["student_id"].unique()

print("\nUnique students:", len(students))


# ==========================================================
# SPLIT STUDENTS
# ==========================================================

# First:
# 70% training
# 30% temporary

train_students, temp_students = train_test_split(
    students,
    test_size=(VAL_RATIO + TEST_RATIO),
    random_state=RANDOM_STATE,
    shuffle=True
)


# Split temporary group equally:
# 15% validation
# 15% testing

val_students, test_students = train_test_split(
    temp_students,
    test_size=0.50,
    random_state=RANDOM_STATE,
    shuffle=True
)


# ==========================================================
# CREATE DATASETS
# ==========================================================

train_df = df[
    df["student_id"].isin(train_students)
].copy()

val_df = df[
    df["student_id"].isin(val_students)
].copy()

test_df = df[
    df["student_id"].isin(test_students)
].copy()


# ==========================================================
# SAVE FILES
# ==========================================================

train_df.to_csv(
    TRAIN_FILE,
    index=False
)

val_df.to_csv(
    VAL_FILE,
    index=False
)

test_df.to_csv(
    TEST_FILE,
    index=False
)


# ==========================================================
# SHOW RESULTS
# ==========================================================

print("\n" + "=" * 70)
print("SPLIT RESULTS")
print("=" * 70)

print(
    f"\nTRAIN:"
    f"\n  Students: {len(train_students)}"
    f"\n  Images:   {len(train_df)}"
)

print(
    f"\nVALIDATION:"
    f"\n  Students: {len(val_students)}"
    f"\n  Images:   {len(val_df)}"
)

print(
    f"\nTEST:"
    f"\n  Students: {len(test_students)}"
    f"\n  Images:   {len(test_df)}"
)


# ==========================================================
# CHECK ACTIVITY DISTRIBUTION
# ==========================================================

for name, split_df in [
    ("TRAIN", train_df),
    ("VALIDATION", val_df),
    ("TEST", test_df)
]:

    print("\n")
    print("-" * 60)
    print(name)
    print("-" * 60)

    if "activity" in split_df.columns:

        print("\nActivity distribution:")

        print(
            split_df["activity"]
            .value_counts()
        )

    if "creativity_level" in split_df.columns:

        print("\nCreativity level distribution:")

        print(
            split_df["creativity_level"]
            .value_counts()
        )


# ==========================================================
# CHECK FOR STUDENT LEAKAGE
# ==========================================================

train_set = set(train_students)
val_set = set(val_students)
test_set = set(test_students)

train_val_overlap = train_set.intersection(val_set)

train_test_overlap = train_set.intersection(test_set)

val_test_overlap = val_set.intersection(test_set)


print("\n" + "=" * 70)
print("DATA LEAKAGE CHECK")
print("=" * 70)

print(
    "Train / Validation overlap:",
    len(train_val_overlap)
)

print(
    "Train / Test overlap:",
    len(train_test_overlap)
)

print(
    "Validation / Test overlap:",
    len(val_test_overlap)
)


if (
    len(train_val_overlap) == 0
    and len(train_test_overlap) == 0
    and len(val_test_overlap) == 0
):

    print("\nStudent leakage: NONE ✅")

else:

    print("\nWARNING: Student leakage detected ❌")


# ==========================================================
# SAVE STUDENT LISTS
# ==========================================================

pd.DataFrame(
    {"student_id": train_students}
).to_csv(
    SPLIT_DIR / "train_students.csv",
    index=False
)

pd.DataFrame(
    {"student_id": val_students}
).to_csv(
    SPLIT_DIR / "val_students.csv",
    index=False
)

pd.DataFrame(
    {"student_id": test_students}
).to_csv(
    SPLIT_DIR / "test_students.csv",
    index=False
)


# ==========================================================
# FINISH
# ==========================================================

print("\nFiles created:")

print(TRAIN_FILE)
print(VAL_FILE)
print(TEST_FILE)

print("\nDataset splitting completed successfully. ✅")