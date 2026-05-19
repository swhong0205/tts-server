FROM python:3.11-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
        git build-essential ffmpeg espeak-ng \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# F5-TTS 모델 미리 다운로드
RUN python -c "from f5_tts.api import F5TTS; F5TTS()"

# 참조 오디오 자동 생성
COPY generate_reference.py .
RUN python generate_reference.py

COPY app.py .

# Sherpa-ONNX 모델은 MODEL_DIR 볼륨으로 마운트
# docker run -v /path/to/model:/app/model -p 8080:8080 tts-unified

EXPOSE 8080
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8080"]
