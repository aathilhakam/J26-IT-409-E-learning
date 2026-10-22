# Component 3: teacher assessment and colour usage

## Behaviour

- New teacher observations start empty. Originality, Imagination, Elaboration and Storytelling each require a whole number from 1 to 10.
- Spring Boot validates the four criterion keys, their values, scale 10, and the submitted average. The average is stored without rounding away quarter points; progress percentages use each record's own `maxScore`.
- The MongoDB `learning_records` collection now has an optional `creativityAssessment` subdocument. It stores the teacher rubric, activity/prompt and separate image/voice analysis snapshots. Existing records are not migrated or rewritten. No database schema migration is necessary for this additive MongoDB field.
- Open an assessment using **Creativity journey → View assessment**. Structured scores and their scale reload from the API. For older records, only explicit values in the teacher `Rubric:` notes are recovered. Missing criteria display **Not recorded**; no criterion is inferred from the average or from AI notes. Historical scales remain unchanged.
- Image predictions retain the configured ResNet18 checkpoint, its five original criteria and its 1–5 scale. The UI no longer offers buttons that copy incompatible image estimates into the teacher rubric. No model is retrained, replaced or rescaled. Voice inference and all model weights are unchanged.
- **AI Model Analysis → Picture · Image analysis → Detected Colour Usage** displays measured categories, mean observed swatches, drawing-pixel percentages, progress bars, dominant category and category count. The saved assessment summary includes the same information.

## Colour method and limits

The image is EXIF-oriented and analysed with Pillow/NumPy HSV categories: Red, Blue, Green, Yellow, Orange, Purple, Pink, Brown, Black and Gray. Colour analysis uses a separate copy, sampled at up to 768 pixels per side. ResNet18 still uses a 224×224 RGB tensor with the original 0.5 mean/std normalization. Transparent exports are composited onto white for model inference only.

Colour percentages use foreground drawing pixels, excluding transparent pixels, near-white pixels (including the canvas's off-white paper), and connected neutral paper estimated from image borders. Empty backgrounds return no colours and no dominant colour. Swatches are computed from the mean RGB of each detected category, not example palette values.

Photo results are approximate: neutral borders and soft shadows can be excluded, but dark shadows, coloured paper, JPEG fringes, coloured lighting, surroundings and pale gray artwork can affect the result. Small extra categories can occur from compression. White drawing marks cannot be distinguished from white paper by this heuristic. The interface explicitly labels these limits. Category count does not affect creativity scores.

## API contracts

Existing authenticated, CSRF-protected Spring Boot routes are unchanged:

| Route | Update |
| --- | --- |
| `POST /api/students/{studentId}/records` | Accepts optional `creativityAssessment`; the creativity UI sends `maxScore: 10` and the exact teacher average as `score`. |
| `GET /api/students/{studentId}/records` | Returns the stored subdocument; historical records have no subdocument/null. |

The new subdocument has these fields:

```text
creativityAssessment:
  teacherScores: { originality, imagination, elaboration, storytelling }  # integers 1–10
  teacherScoreScale: 10
  activity: string
  prompt: string
  imageAnalysis: object | null   # original 1–5 AI scores plus colour analysis
  voiceAnalysis: object | null   # unchanged WavLM response
  imageAnalysisError: string | null
  voiceAnalysisError: string | null
  voiceRecorded: boolean
```

The existing Python endpoint `POST /predict/image` accepts multipart field `file` (PNG/JPEG/other Pillow-supported images; up to 15 MB and 40 million pixels). In development it is reached through `POST /image-ai/predict/image`. Image inference goes directly through the Vite proxy to Python; Spring Boot persists the resulting snapshot with the assessment.

The response retains `model`, `scores`, `average_score`, evaluation metadata and teacher-review messaging, and adds:

```text
score_scale: 5
prediction_error: string | null
colour_analysis:
  colours: [{ name, hex, percentage, pixel_count }]
  dominant_colour: string | null
  category_count: integer
  drawing_pixels: integer
  analysed_pixels: integer
  approximate: true
  method: string
  message: string
```

On a checkpoint/inference failure, the response explicitly returns `scores: null`, `average_score: null` and `prediction_error`, while retaining actual colour results. It does not fabricate scores or substitute another model. Invalid files return HTTP 400; oversized files return 413. `GET /health` reports `running` or `degraded`, `prediction_error` and `colour_analysis_available`.

## Investigation of the previous HTTP 500

The checked-in checkpoint loaded strictly against the existing ResNet18 architecture, and original inference succeeded on a repository sample. The existing service on port 8003 also returned HTTP 200 through Vite for that sample. The earlier 500 was **not reproduced**, and its original traceback was unavailable, so its historical cause cannot be established from this session.

One concrete integration problem was found: an already-running Python process returned the old response without colour analysis. Vite previously reused that process based only on its model name. Startup now checks the colour capability and explains that the old service must be restarted. The frontend also gives a specific error for old responses.

Inference now runs in a synchronous FastAPI worker instead of blocking the async event loop. Checkpoint/inference exceptions are logged with tracebacks; colour results survive model failure. A missing evaluation JSON no longer prevents an otherwise valid checkpoint from serving. These are explicit error-handling improvements, not a claimed diagnosis of the unavailable earlier traceback.

## Start on Windows

From the repository root, with local MongoDB running:

```powershell
cd backend
mvn spring-boot:run
```

In a separate terminal:

```powershell
cd frontend
npm.cmd install   # only if dependencies are not installed
npm.cmd run dev
```

Vite starts the image service automatically using the repository `.venv`. If port 8003 has an old process, stop that process in its terminal and restart Vite. Alternatively, choose a free image-service port before starting Vite:

```powershell
$env:SMARTPLAY_IMAGE_SERVICE_PORT = '8004'
npm.cmd run dev
```

To manage Python separately, run this from the repository root (before starting Vite):

```powershell
.\.venv\Scripts\python.exe -m uvicorn src.image_creativity_api:app --app-dir .\ai-service --host 127.0.0.1 --port 8003
```

Optional existing WavLM voice service, in another terminal:

```powershell
.\.venv\Scripts\python.exe -m uvicorn src.voice_wavlm_api:app --app-dir .\ai-service --host 127.0.0.1 --port 8000
```

The existing Python environment must contain `ai-service/requirements.txt` dependencies. Model inference needs `ai-service/models/resnet18_scratch.pth`; metrics are read from `ai-service/results/resnet18_metrics.json`. Missing/incompatible checkpoints are reported explicitly. `VITE_IMAGE_API_URL`, `VITE_WAVLM_API_URL` and `VITE_API_URL` remain the deployment API-base settings. Production hosting must manage Python services and routing separately.

## Verification

| Check | Result |
| --- | --- |
| `frontend`: `npm.cmd test` | 4 passed: blank/valid rubric, historical values/scales, structured reload, no AI-to-teacher recovery. |
| `frontend`: `npm.cmd run build` | Passed. |
| `backend`: `mvn test` | 22 passed: existing tests plus teacher validation, HTTP POST/GET and mixed historical scales. |
| `backend`: `mvn test -Dtest=CreativityMongoIT` | 1 passed against local MongoDB, using a unique disposable database; nested teacher/AI values survived an actual database round trip. Only that temporary test database was removed. |
| Repository root: `.\.venv\Scripts\python.exe -B -m unittest discover -s ai-service/tests -v` | 9 passed: colours, percentages, transparency/off-white, shadows, real checkpoint inference, invalid/oversized uploads, missing checkpoint and inference failure. |
| Live Vite → Python multipart requests | HTTP 200 with real checkpoint scores and colour results for canvas PNG, uploaded JPEG and simulated dim-paper camera JPEG. The PNG fixture measured Blue 75%, Red 25% after background exclusion. |

PowerShell blocked `npm.ps1`; using `npm.cmd` resolved that without changing execution policy. Maven required access to its dependency cache outside the workspace. Existing Mockito/JDK agent warnings did not fail the suite.

Visual browser verification could not run because no browser automation surface was available. A physical camera capture and microphone/voice inference were not exercised; the camera uses the same submitted-image upload path. The live production Spring Boot server was not running during HTTP image checks; its request/save/reload path was covered by MockMvc plus the independent real MongoDB round trip. Photo classification remains approximate, and the historical 500 still requires its original failing input/log to diagnose conclusively.

## Changed files

- `.gitignore` — ignore generated Python caches.
- `README.md` — link this Component 3 guide and update image-service startup details.
- `ai-service/src/colour_analysis.py` — independent colour analysis.
- `ai-service/src/image_creativity_api.py` — preprocessing, colour response, health and explicit model errors.
- `ai-service/tests/test_image_analysis.py` — colour and real-checkpoint tests.
- `backend/src/main/java/lk/smartplay/adaptive/model/CreativityAssessment.java` — independent structured snapshots.
- `backend/src/main/java/lk/smartplay/adaptive/model/LearningRecord.java` — optional persisted assessment.
- `backend/src/main/java/lk/smartplay/adaptive/dto/CreateLearningRecordRequest.java` — additive request field.
- `backend/src/main/java/lk/smartplay/adaptive/service/StudentService.java` — rubric/scale/average validation.
- `backend/src/test/java/lk/smartplay/adaptive/service/StudentServiceTest.java` — validation and historical-scale tests.
- `backend/src/test/java/lk/smartplay/adaptive/service/CreativityMongoIT.java` — opt-in actual database round trip.
- `backend/src/test/java/lk/smartplay/adaptive/controller/CreativityRecordControllerTest.java` — HTTP serialization/validation tests.
- `frontend/package.json` — focused test command.
- `frontend/src/app/App.jsx` — send structured 10-point teacher observation.
- `frontend/src/features/creativity/assessment.js` — historical/structured record decoding and score rules.
- `frontend/src/features/creativity/assessment.test.js` — decoding and score tests.
- `frontend/src/features/creativity/CreativityLab.jsx` — blank fields, independent scores, saved review and colour UI.
- `frontend/src/services/api.js` — colour/partial-result contract and stale-service message.
- `frontend/src/styles.css` — colour swatches and usage bars.
- `frontend/vite.config.js` — capability check and optional local service port.
- `docs/creativity-assessment.md` — this implementation and verification guide.
