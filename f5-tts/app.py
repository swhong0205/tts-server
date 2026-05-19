from contextlib import asynccontextmanager
from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import Response
from pydantic import BaseModel
import os, io, logging, soundfile as sf

logging.basicConfig(level=logging.INFO)
log = logging.getLogger(__name__)

# 참조 오디오 경로 (10~30초 한국어 음성 파일)
REF_AUDIO = os.getenv("REFERENCE_AUDIO", "reference.wav")
REF_TEXT  = os.getenv("REFERENCE_TEXT", "")   # 비워두면 자동 인식

_model: dict = {}


@asynccontextmanager
async def lifespan(app: FastAPI):
    if not os.path.exists(REF_AUDIO):
        raise RuntimeError(
            f"참조 오디오 없음: {REF_AUDIO}\n"
            "10~30초 한국어 음성 파일을 reference.wav로 저장하거나\n"
            "REFERENCE_AUDIO 환경변수로 경로를 지정하세요."
        )

    ref_text = REF_TEXT
    if not ref_text and os.path.exists("reference.txt"):
        ref_text = open("reference.txt", encoding="utf-8").read().strip()

    log.info("F5-TTS 모델 로딩 중...")
    from f5_tts.api import F5TTS
    _model["tts"]       = F5TTS()
    _model["ref_audio"] = REF_AUDIO
    _model["ref_text"]  = ref_text
    log.info("모델 로딩 완료")
    yield


app = FastAPI(title="Korean TTS API (F5-TTS)", version="1.0.0", lifespan=lifespan)


class SynthRequest(BaseModel):
    text: str
    speed: float = 1.0


def _synthesize(text: str, speed: float) -> bytes:
    text = text.strip()
    if not text:
        raise HTTPException(400, "text must not be empty")
    if len(text) > 500:
        raise HTTPException(400, "text too long (max 500 chars)")

    wav, sr, _ = _model["tts"].infer(
        ref_file=_model["ref_audio"],
        ref_text=_model["ref_text"],
        gen_text=text,
        speed=speed,
    )
    bio = io.BytesIO()
    sf.write(bio, wav, sr, format="wav")
    return bio.getvalue()


@app.get("/health")
def health():
    return {"status": "ok", "model": "F5-TTS", "ref_audio": _model.get("ref_audio")}


@app.post("/tts")
def synthesize_post(req: SynthRequest):
    return Response(content=_synthesize(req.text, req.speed), media_type="audio/wav")


@app.get("/tts")
def synthesize_get(
    text: str = Query(..., description="합성할 텍스트"),
    speed: float = Query(1.0, description="말하기 속도 (0.5~2.0)"),
):
    return Response(content=_synthesize(text, speed), media_type="audio/wav")
