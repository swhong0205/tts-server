FROM python:3.11-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
        git build-essential ffmpeg espeak-ng \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# F5-TTS 모델 미리 다운로드
RUN python -c "from f5_tts.api import F5TTS; F5TTS()"

COPY generate_reference.py .
# 참조 오디오 생성 (gTTS → espeak-ng 순으로 시도)
RUN python generate_reference.py

COPY app.py .

EXPOSE 8000
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
