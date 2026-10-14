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
