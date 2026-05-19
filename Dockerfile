# ============================================================
# 한국어 TTS 통합 서버 (Sherpa-ONNX + F5-TTS)
# ============================================================
FROM python:3.11-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
        git build-essential ffmpeg espeak-ng curl bzip2 \
    && rm -rf /var/lib/apt/lists/*

# Python 의존성
COPY tts-unified/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# F5-TTS 모델 미리 다운로드
RUN python -c "from f5_tts.api import F5TTS; F5TTS()"

# 참조 오디오 생성 (F5-TTS용)
COPY tts-unified/generate_reference.py .
RUN python generate_reference.py

# Sherpa-ONNX 한국어 모델 다운로드
RUN mkdir -p /app/model \
    && curl -fL \
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-mimic3-ko_KO-kss_low.tar.bz2" \
        -o /tmp/model.tar.bz2 \
    && tar -xjf /tmp/model.tar.bz2 -C /tmp/ \
    && cp -r /tmp/vits-mimic3-ko_KO-kss_low/. /app/model/ \
    && rm -rf /tmp/model.tar.bz2 /tmp/vits-mimic3-ko_KO-kss_low

COPY tts-unified/app.py .

ENV MODEL_DIR=/app/model \
    PORT=8080

EXPOSE 8080
CMD ["sh", "-c", "uvicorn app:app --host 0.0.0.0 --port $PORT"]
