from contextlib import asynccontextmanager
from fastapi import FastAPI, HTTPException
from fastapi.responses import Response, HTMLResponse
from pydantic import BaseModel
import os, io, glob, wave, logging
import numpy as np

logging.basicConfig(level=logging.INFO)
log = logging.getLogger(__name__)

_engines: dict = {}


@asynccontextmanager
async def lifespan(app: FastAPI):
    # ── Sherpa-ONNX ───────────────────────────────────────────
    model_dir = os.getenv("MODEL_DIR", "/app/model")
    try:
        import sherpa_onnx
        onnx_files = glob.glob(f"{model_dir}/*.onnx")
        tokens     = f"{model_dir}/tokens.txt"
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
        _engines["sherpa"] = sherpa_onnx.OfflineTts(cfg)
        log.info("✓ Sherpa-ONNX 로드 완료")
    except Exception as e:
        log.warning(f"Sherpa-ONNX 로드 실패 (선택적): {e}")

    # ── F5-TTS ────────────────────────────────────────────────
    ref_audio = os.getenv("REFERENCE_AUDIO", "reference.wav")
    try:
        if not os.path.exists(ref_audio):
            raise FileNotFoundError(f"reference.wav 없음: {ref_audio}")

        ref_text = ""
        if os.path.exists("reference.txt"):
            ref_text = open("reference.txt", encoding="utf-8").read().strip()

        from f5_tts.api import F5TTS
        _engines["f5"] = {"tts": F5TTS(), "ref_audio": ref_audio, "ref_text": ref_text}
        log.info("✓ F5-TTS 로드 완료")
    except Exception as e:
        log.warning(f"F5-TTS 로드 실패 (선택적): {e}")

    if not _engines:
        raise RuntimeError("사용 가능한 TTS 엔진이 없습니다")

    yield


app = FastAPI(title="Korean TTS", version="1.0.0", lifespan=lifespan)


class SynthRequest(BaseModel):
    text: str
    engine: str = "sherpa"
    speed: float = 1.0
    sid: int = 0


@app.get("/health")
def health():
    return {
        "status": "ok",
        "engines": {k: True for k in _engines},
    }


@app.post("/tts")
def synthesize(req: SynthRequest):
    text = req.text.strip()
    if not text:
        raise HTTPException(400, "text must not be empty")

    if req.engine == "sherpa":
        if "sherpa" not in _engines:
            raise HTTPException(503, "Sherpa-ONNX 엔진 사용 불가")
        audio = _engines["sherpa"].generate(text, sid=req.sid, speed=req.speed)
        wav_bytes = _samples_to_wav(np.array(audio.samples), audio.sample_rate)

    elif req.engine == "f5":
        if "f5" not in _engines:
            raise HTTPException(503, "F5-TTS 엔진 사용 불가")
        import soundfile as sf
        f5 = _engines["f5"]
        wav, sr, _ = f5["tts"].infer(
            ref_file=f5["ref_audio"],
            ref_text=f5["ref_text"],
            gen_text=text,
            speed=req.speed,
        )
        bio = io.BytesIO()
        sf.write(bio, wav, sr, format="wav")
        wav_bytes = bio.getvalue()

    else:
        raise HTTPException(400, f"알 수 없는 엔진: {req.engine}")

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


# ── 웹 UI ──────────────────────────────────────────────────────
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
      width: 100%; max-width: 560px;
      box-shadow: 0 4px 24px rgba(0,0,0,0.10);
    }
    h1 { font-size: 1.4rem; color: #1a1a2e; margin-bottom: 24px; }
    label { display: block; font-size: 0.85rem; color: #555; margin-bottom: 6px; font-weight: 500; }
    .engine-toggle {
      display: flex; background: #f0f2f5; border-radius: 10px;
      padding: 4px; margin-bottom: 20px; gap: 4px;
    }
    .engine-btn {
      flex: 1; padding: 9px 0; border: none; border-radius: 7px;
      font-size: 0.88rem; font-weight: 600; cursor: pointer;
      background: transparent; color: #888; transition: all .2s;
      width: auto; margin-top: 0;
    }
    .engine-btn.active {
      background: #fff; color: #4f6ef7;
      box-shadow: 0 1px 6px rgba(0,0,0,0.12);
    }
    .engine-badge {
      display: inline-block; font-size: 0.75rem; padding: 2px 8px;
      border-radius: 99px; margin-bottom: 20px;
      background: #ebf0ff; color: #4f6ef7; font-weight: 600;
    }
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
    .disabled-group { opacity: 0.35; pointer-events: none; }
  </style>
</head>
<body>
<div class="card">
  <h1>🔊 한국어 TTS 데모</h1>

  <label>엔진 선택</label>
  <div class="engine-toggle">
    <button class="engine-btn active" id="btn-sherpa" onclick="selectEngine('sherpa')">Sherpa-ONNX</button>
    <button class="engine-btn"        id="btn-f5"     onclick="selectEngine('f5')">F5-TTS</button>
  </div>

  <div id="engine-badge" class="engine-badge">Sherpa-ONNX · vits-kss</div>

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
    <div class="control-group" id="sid-group">
      <label>화자 ID (Sherpa 전용)</label>
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
  let engine = 'sherpa';

  document.getElementById('speed').oninput = e =>
    document.getElementById('speedVal').textContent = (+e.target.value).toFixed(1);
  document.getElementById('sid').oninput = e =>
    document.getElementById('sidVal').textContent = e.target.value;

  const BADGES = {
    sherpa: 'Sherpa-ONNX · vits-kss',
    f5:     'F5-TTS · zero-shot',
  };

  function selectEngine(e) {
    engine = e;
    document.getElementById('btn-sherpa').classList.toggle('active', e === 'sherpa');
    document.getElementById('btn-f5').classList.toggle('active', e === 'f5');
    document.getElementById('engine-badge').textContent = BADGES[e];
    document.getElementById('sid-group').classList.toggle('disabled-group', e === 'f5');
  }

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
        body: JSON.stringify({ text, speed, sid, engine }),
      });

      if (!res.ok) {
        const err = await res.json().catch(() => ({ detail: res.statusText }));
        throw new Error(err.detail || res.statusText);
      }

      const blob = await res.blob();
      const elapsed = Date.now() - t0;
      player.src = URL.createObjectURL(blob);
      player.classList.add('visible');
      player.play();
      showStatus('success', `✅ ${BADGES[engine]} 완료 (${elapsed} ms · ${(blob.size/1024).toFixed(1)} KB)`);
    } catch(e) {
      showStatus('error', '❌ 오류: ' + e.message);
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
