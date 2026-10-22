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

To enable recorded voice analysis with WavLM, make sure `voice_wavlm_classifier.pth` and `voice_wavlm_scaler.joblib` exist under `ai-service/models`. Start the existing WavLM service from the repository root:

```powershell
.\.venv\Scripts\python.exe -m uvicorn src.voice_wavlm_api:app --app-dir .\ai-service --host 127.0.0.1 --port 8000
```

The frontend proxies `/ai` to this service; set `VITE_WAVLM_API_URL` to a different API base when deploying. The browser uploads its native microphone recording format. The API converts WAV, OGG, WebM, MP4/M4A, or MP3 recordings (up to 15 MB) to mono WAV at the model's sample rate with the installed `imageio-ffmpeg` binary. Audio is processed in a temporary directory and deleted after prediction. WavLM returns Developing/Moderate/High probabilities, not rubric scores or a transcript. Its prior held-out accuracy was 62.5% on 8 recordings, below the 75% majority-class baseline, so treat output as experimental and keep teacher assessment authoritative.

The classroom voice-analysis path now uses WavLM; the separate Custom 1D CNN experiment and its saved files are left untouched.

To enable image analysis with the trained ResNet18 model, start the image service from the repository root. The `resnet18_scratch.pth` checkpoint and its evaluation report must be present under `ai-service/models` and `ai-service/results`:

```powershell
.\.venv\Scripts\python.exe -m uvicorn src.image_creativity_api:app --app-dir .\ai-service --host 127.0.0.1 --port 8003
```

The frontend proxies `/image-ai` to this service. Set `VITE_IMAGE_API_URL` to a different API base when deploying. On drawing activities, ResNet18 estimates five visual dimensions on a 1–5 scale. Image and audio are processed separately and are not retained after analysis. The model outputs are experimental suggestions; teachers review them independently, and the teacher-entered rubric remains authoritative.

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
