from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException
from fastapi.encoders import jsonable_encoder

from src.recommendation.service import RecommendationService


service = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global service
    service = RecommendationService.from_project_root()
    yield
    service = None


app = FastAPI(
    title="SmartPlay Recommendation API",
    version="1.0.0",
    lifespan=lifespan,
)


@app.get("/health")
def health():
    return {
        "status": "ok",
        "service": "SmartPlay Recommendation API"
    }


@app.get("/api/recommendations/{student_id}")
def get_recommendation(student_id: str):

    if service is None:
        raise HTTPException(
            status_code=503,
            detail="Recommendation model is not ready"
        )

    result = service.get_student_recommendation(student_id)

    if result.get("Status") == "Student not found":
        raise HTTPException(
            status_code=404,
            detail=f"Student {student_id} not found"
        )

    return jsonable_encoder(result)