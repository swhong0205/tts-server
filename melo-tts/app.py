from contextlib import asynccontextmanager
from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import Response
from pydantic import BaseModel
import os, tempfile, logging

logging.basicConfig(level=logging.INFO)
log = logging.getLogger(__name__)

_model: dict = {}


@asynccontextmanager
async def lifespan(app: FastAPI):
    log.info("MeloTTS KR 모델 로딩 중...")
    from melo.api import TTS
    tts = TTS(language="KR", device="auto")
    _model["tts"] = tts
    _model["speaker_id"] = tts.hps.data.spk2id["KR"]
    _model["sample_rate"] = tts.hps.data.sampling_rate
    log.info(f"모델 로딩 완료 (sample_rate={_model['sample_rate']})")
    yield


app = FastAPI(title="Korean TTS API (MeloTTS)", version="1.0.0", lifespan=lifespan)


class SynthRequest(BaseModel):
    text: str
    speed: float = 1.0


@app.get("/health")
def health():
    return {"status": "ok", "model": "MeloTTS KR", "sample_rate": _model.get("sample_rate")}


@app.post("/tts")
def synthesize(req: SynthRequest):
    text = req.text.strip()
    if not text:
        raise HTTPException(status_code=400, detail="text must not be empty")
    if len(text) > 500:
        raise HTTPException(status_code=400, detail="text too long (max 500 chars)")

    tts = _model["tts"]
    speaker_id = _model["speaker_id"]

    tmp = tempfile.mktemp(suffix=".wav")
    try:
        tts.tts_to_file(text, speaker_id, tmp, speed=req.speed)
        with open(tmp, "rb") as f:
            wav_bytes = f.read()
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)

    return Response(content=wav_bytes, media_type="audio/wav")


@app.get("/tts")
def synthesize_get(
    text: str = Query(..., description="합성할 텍스트"),
    speed: float = Query(1.0, description="말하기 속도 (0.5~2.0)"),
):
    return synthesize(SynthRequest(text=text, speed=speed))
