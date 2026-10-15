# SmartPlay AI Recommendation

SmartPlay's recommendation-modeling workspace for exploring data, preparing features, training models, and evaluating recommendations. Trained artifacts are saved in `models/` for the backend to consume through a Python service or precomputed recommendation tables.

## Project structure

```text
data/
  raw/                  Original input data
  processed/            Cleaned and transformed data
notebooks/
  01_data_exploration.ipynb
  02_preprocessing.ipynb
  03_feature_engineering.ipynb
  04_model_training.ipynb
  05_model_evaluation.ipynb
src/
  preprocessing/        Data cleaning and transformation
  features/             Feature creation
  models/               Model definitions and training
  recommendation/       Recommendation logic
  evaluation/           Evaluation metrics and utilities
  utils/                Shared helpers
models/                 Exported model artifacts
tests/                  Automated tests
```

## Getting started

Requires Python 3.10 or later.

```bash
python -m venv .venv
source .venv/bin/activate       # Windows: .venv\Scripts\activate
pip install -r requirements.txt
jupyter notebook
```

Run the notebooks in numerical order. Place source datasets in `data/raw/`; save reproducible intermediate datasets to `data/processed/`. Export trained artifacts to `models/`. Data and generated model files should not be committed unless they are explicitly intended to be versioned.

## Backend integration

The Spring Boot backend can load exported model artifacts via a Python microservice or consume precomputed recommendation tables. Document the API contract and JSON schemas alongside the backend integration.
