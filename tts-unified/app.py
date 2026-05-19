from contextlib import asynccontextmanager
from fastapi import FastAPI, HTTPException
from fastapi.responses import Response, HTMLResponse
from pydantic import BaseModel
import os, io, glob, wave, logging
import numpy as np

logging.basicConfig(level=logging.INFO)
log = logging.getLogger(__name__)

_tts = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global _tts
    model_dir = os.getenv("MODEL_DIR", "/app/model")
    import sherpa_onnx
    onnx_files = glob.glob(f"{model_dir}/*.onnx")
    tokens = f"{model_dir}/tokens.txt"
    espeak_dir = f"{model_dir}/espeak-ng-data"

    if not onnx_files:
        raise FileNotFoundError(f".onnx 없음: {model_dir}")
    if not os.path.exists(tokens):
        raise FileNotFoundError(f"tokens.txt 없음: {model_dir}")

    cfg = sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(
                model=onnx_files[0],
                tokens=tokens,
                data_dir=espeak_dir if os.path.isdir(espeak_dir) else "",
                lexicon="",
            ),
            num_threads=2,
        ),
        max_num_sentences=1,
    )
    _tts = sherpa_onnx.OfflineTts(cfg)
    log.info("Sherpa-ONNX 로드 완료")
    yield


app = FastAPI(title="Korean TTS", version="1.0.0", lifespan=lifespan)


class SynthRequest(BaseModel):
    text: str
    speed: float = 1.0
    sid: int = 0


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/tts")
def synthesize(req: SynthRequest):
    text = req.text.strip()
    if not text:
        raise HTTPException(400, "text must not be empty")
    audio = _tts.generate(text, sid=req.sid, speed=req.speed)
    wav_bytes = _samples_to_wav(np.array(audio.samples), audio.sample_rate)
    return Response(content=wav_bytes, media_type="audio/wav")


def _samples_to_wav(samples: np.ndarray, sample_rate: int) -> bytes:
    bio = io.BytesIO()
    with wave.open(bio, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sample_rate)
        wf.writeframes((samples * 32767).astype(np.int16).tobytes())
    return bio.getvalue()


@app.get("/", response_class=HTMLResponse)
def ui():
    return HTML


HTML = """<!DOCTYPE html>
<html lang="ko">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>한국어 TTS 데모</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: 'Noto Sans KR', 'Apple SD Gothic Neo', sans-serif;
      background: #f0f2f5;
      display: flex; justify-content: center; align-items: center;
      min-height: 100vh; padding: 20px;
    }
    .card {
      background: #fff; border-radius: 16px; padding: 36px 40px;
      width: 100%; max-width: 520px;
      box-shadow: 0 4px 24px rgba(0,0,0,0.10);
    }
    h1 { font-size: 1.4rem; color: #1a1a2e; margin-bottom: 24px; }
    label { display: block; font-size: 0.85rem; color: #555; margin-bottom: 6px; font-weight: 500; }
    textarea {
      width: 100%; height: 120px; border: 1.5px solid #dde1e7;
      border-radius: 10px; padding: 12px 14px; font-size: 1rem;
      resize: vertical; outline: none; transition: border-color .2s;
      font-family: inherit; color: #222;
    }
    textarea:focus { border-color: #4f6ef7; }
    .controls { display: flex; gap: 16px; margin: 16px 0; }
    .control-group { flex: 1; }
    input[type=range] { width: 100%; accent-color: #4f6ef7; }
    .range-row { display: flex; justify-content: space-between; align-items: center; }
    .range-val { font-size: 0.85rem; color: #4f6ef7; font-weight: 600; min-width: 32px; text-align: right; }
    .synth-btn {
      width: 100%; padding: 14px; background: #4f6ef7; color: #fff;
      border: none; border-radius: 10px; font-size: 1rem; font-weight: 600;
      cursor: pointer; transition: background .2s, transform .1s; margin-top: 8px;
    }
    .synth-btn:hover:not(:disabled) { background: #3a57e8; }
    .synth-btn:active:not(:disabled) { transform: scale(0.98); }
    .synth-btn:disabled { background: #a0aec0; cursor: not-allowed; }
    .status {
      margin-top: 16px; padding: 10px 14px; border-radius: 8px;
      font-size: 0.88rem; display: none;
    }
    .status.loading { background: #ebf0ff; color: #4f6ef7; display: block; }
    .status.success { background: #e6faf2; color: #1a7f5a; display: block; }
    .status.error   { background: #fff0f0; color: #c0392b; display: block; }
    audio { width: 100%; margin-top: 16px; border-radius: 8px; display: none; }
    audio.visible { display: block; }
  </style>
</head>
<body>
<div class="card">
  <h1>한국어 TTS 데모</h1>

  <label for="text">합성할 텍스트</label>
  <textarea id="text" placeholder="여기에 한국어 텍스트를 입력하세요.">안녕하세요. 한국어 TTS 데모입니다.</textarea>

  <div class="controls">
    <div class="control-group">
      <label>말하기 속도</label>
      <div class="range-row">
        <input type="range" id="speed" min="0.5" max="2.0" step="0.1" value="1.0">
        <span class="range-val" id="speedVal">1.0</span>
      </div>
    </div>
    <div class="control-group">
      <label>화자 ID</label>
      <div class="range-row">
        <input type="range" id="sid" min="0" max="10" step="1" value="0">
        <span class="range-val" id="sidVal">0</span>
      </div>
    </div>
  </div>

  <button class="synth-btn" id="btn" onclick="synthesize()">음성 합성</button>
  <div class="status" id="status"></div>
  <audio id="player" controls></audio>
</div>

<script>
  document.getElementById('speed').oninput = e =>
    document.getElementById('speedVal').textContent = (+e.target.value).toFixed(1);
  document.getElementById('sid').oninput = e =>
    document.getElementById('sidVal').textContent = e.target.value;

  async function synthesize() {
    const text  = document.getElementById('text').value.trim();
    const speed = parseFloat(document.getElementById('speed').value);
    const sid   = parseInt(document.getElementById('sid').value);
    const btn   = document.getElementById('btn');
    const player = document.getElementById('player');

    if (!text) { showStatus('error', '텍스트를 입력해 주세요.'); return; }

    btn.disabled = true;
    showStatus('loading', '⏳ 음성 합성 중...');
    player.classList.remove('visible');

    try {
      const t0 = Date.now();
      const res = await fetch('/tts', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text, speed, sid }),
      });

      if (!res.ok) {
        const err = await res.json().catch(() => ({ detail: res.statusText }));
        throw new Error(err.detail || res.statusText);
      }

      const blob = await res.blob();
      player.src = URL.createObjectURL(blob);
      player.classList.add('visible');
      player.play();
      showStatus('success', `완료 (${Date.now() - t0} ms · ${(blob.size/1024).toFixed(1)} KB)`);
    } catch(e) {
      showStatus('error', '오류: ' + e.message);
    } finally {
      btn.disabled = false;
    }
  }

  function showStatus(type, msg) {
    const el = document.getElementById('status');
    el.className = 'status ' + type;
    el.textContent = msg;
  }
</script>
</body>
</html>"""
