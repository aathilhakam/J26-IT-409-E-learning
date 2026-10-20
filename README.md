# SmartPlay — Teacher-Centered Adaptive Learning

SmartPlay is the React and Spring Boot prototype for Component 1 of the J26-IT-409 e-learning research project. It helps a teacher keep Grade 1 learning records, see a simple progress profile, and review activity suggestions before using them with a learner.

## What is included

- A React JavaScript teacher dashboard with a learner list and responsive layout.
- Student profiles and longitudinal learning records saved in MongoDB.
- Per-area averages, strengths/support indicators, and a simple progress trend.
- Explainable activity suggestions based on the learner's lowest recorded area.
- Teacher approval, rejection with optional feedback, and edit-before-approval controls.
- A Spring Boot REST API with request validation and focused unit tests.

The recommendation logic in this prototype is deterministic and score-based. It is **not** a trained AI model and its displayed match value is a heuristic, not a measured probability. A teacher remains responsible for reviewing each suggestion. Do not use real children's personal data in this demonstration build.

## Requirements

- Java 17 or later
- Maven 3.9+
- Node.js 20+
- MongoDB Community Server running locally on the default port (`27017`)

By default, the API uses `mongodb://localhost:27017/smartplay`. Override it with `MONGODB_URI` if your local MongoDB uses another address or database.

## Run locally

Start your MongoDB service first. For example, with Homebrew's MongoDB Community formula installed:

```sh
brew services start mongodb-community
```

In a terminal, start the backend:

```sh
cd backend
mvn spring-boot:run
```

In another terminal, start the frontend:

```sh
cd frontend
npm install
npm run dev
```

Open the Vite URL shown in the terminal (normally `http://localhost:5173`). Vite proxies `/api` requests to Spring Boot at `http://localhost:8080`.

To enable recorded-story analysis using the trained Random Forest regressor, start its voice service in a third terminal. The `voice_random_forest.joblib` model must be present under `ai-service/models`:

```sh
cd ai-service
uvicorn src.voice_random_forest_api:app --host 127.0.0.1 --port 8002
```

The frontend proxies `/voice-ai` to this service. Set `VITE_VOICE_API_URL` to a different API base when deploying. Story recordings are converted to mono 16 kHz WAV and limited to 60 seconds for analysis. The model estimates six 1–5 vocal-delivery dimensions (expressiveness, fluency, rhythm, vocal variety, engagement, and naturalness); it does not transcribe speech or grade story creativity. These experimental estimates require teacher review and do not replace the teacher-entered creativity rubric.

To train the image Random Forest on the teacher-rated images and start the image analysis service, run these commands from `ai-service`:

```sh
cd ai-service
python src/train_image_random_forest.py
uvicorn src.image_creativity_api:app --host 127.0.0.1 --port 8003
```

Training uses `data/splits/train.csv` and `val.csv` for the labeled image features and holds `test.csv` aside for evaluation; the script checks the student-level splits and reports the test MAE against a training-mean baseline before saving `models/image_random_forest.joblib`. The frontend proxies `/image-ai` to this service. Set `VITE_IMAGE_API_URL` to a different API base when deploying. On drawing activities, the image Random Forest estimates the five teacher-scored visual creativity dimensions; teachers can also record an optional explanation for the separate voice Random Forest. The image is processed in memory; the voice service temporarily writes its audio input and removes it after inference. Neither service retains media after analysis. Both models provide experimental estimates that require teacher review, and their scores are never merged; the teacher-entered rubric remains authoritative.

The WavLM service is optional and separate; start it only when explicitly using the WavLM classifier:

```sh
uvicorn src.voice_wavlm_api:app --host 127.0.0.1 --port 8000
```

To point the frontend at a deployed API, set `VITE_API_URL` to the API base path (for example, `https://example.test/api`) before building. Configure the backend's allowed browser origin in `backend/src/main/java/lk/smartplay/adaptive/config/WebConfig.java` for non-local deployments.

## Verify

```sh
cd backend
mvn test
```

```sh
cd frontend
npm run build
```

## API overview

| Method | Route | Purpose |
| --- | --- | --- |
| `GET` | `/api/students` | List learners |
| `POST` | `/api/students` | Create a learner (`name`, `grade`) |
| `GET` | `/api/students/{id}/profile` | Get progress summary and learning-area trends |
| `GET` | `/api/students/{id}/records` | List learning records |
| `POST` | `/api/students/{id}/records` | Add a learning record (`domain`, `score`, `maxScore`, optional `notes`) |
| `GET` | `/api/students/{id}/recommendations` | List generated pathways |
| `POST` | `/api/students/{id}/recommendations` | Generate an explainable suggestion from learning records |
| `PATCH` | `/api/recommendations/{id}` | Teacher-review or edit a suggestion (`status`: `APPROVED` or `REJECTED`) |

## Project layout

```text
backend/    Spring Boot REST API and MongoDB persistence
frontend/   React JavaScript + Vite dashboard
```

## Prototype limitations

This is a local research prototype: it does not yet include user authentication, authorization, classroom/teacher account management, child-data consent controls, deployment configuration, or a production AI model. Add those safeguards and review applicable privacy/education requirements before any real classroom use.
