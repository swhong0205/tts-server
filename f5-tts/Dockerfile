FROM python:3.11-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
        git build-essential ffmpeg \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 모델 미리 다운로드 (cold start 방지)
RUN python -c "from f5_tts.api import F5TTS; F5TTS()"

COPY app.py .

# reference.wav + reference.txt 는 볼륨 또는 빌드 시 COPY로 제공
# docker run -v /path/to/reference.wav:/app/reference.wav ...

EXPOSE 8000
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
