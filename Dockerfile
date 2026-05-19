# ============================================================
# sherpa-onnx 한국어 TTS PoC - Dockerfile
#
# 멀티 스테이지 빌드:
#   Stage 1 (builder): Gradle + JDK 17 → Fat JAR 생성
#   Stage 2 (runtime): JRE 17 (Ubuntu/Jammy) + 모델 포함
#
# 한국어 모델(vits-mimic3-ko_KO-kss_low, ~64MB)을 빌드 시점에
# 이미지 안에 포함하여 런타임 네트워크 의존성을 제거합니다.
# ============================================================

# ----------------------------------------------------------
# Stage 1: 빌드 스테이지
# ----------------------------------------------------------
FROM gradle:8.7-jdk17-jammy AS builder

WORKDIR /build

COPY build.gradle settings.gradle ./
COPY gradle/           gradle/
COPY libs/             libs/
RUN gradle dependencies --no-daemon --quiet 2>&1 || true

COPY src/ src/
RUN gradle shadowJar --no-daemon --no-build-cache \
    && echo "[빌드 완료]" && ls -lh build/libs/

# ----------------------------------------------------------
# Stage 2: 런타임 스테이지
# ----------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy AS runtime

LABEL description="sherpa-onnx 한국어 TTS PoC (Java 17 / Ubuntu Jammy)"

# 의존 패키지 설치
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        libstdc++6 \
        libgomp1 \
        locales \
        curl \
    && locale-gen ko_KR.UTF-8 \
    && update-locale LANG=ko_KR.UTF-8 \
    && rm -rf /var/lib/apt/lists/*

ENV LANG=ko_KR.UTF-8 \
    LC_ALL=ko_KR.UTF-8

WORKDIR /app

# Fat JAR 및 네이티브 .so 복사
COPY --from=builder /build/build/libs/tts-poc.jar /app/tts-poc.jar
COPY libs/*.so /app/libs/

# ----------------------------------------------------------
# 한국어 TTS 모델을 빌드 시점에 다운로드 (런타임 의존성 제거)
#
# 모델: vits-mimic3-ko_KO-kss_low (~64MB, sherpa-onnx 공식 한국어 모델)
# 파일 구조:
#   /app/model/ko_KO-kss_low.onnx
#   /app/model/tokens.txt
#   /app/model/espeak-ng-data/    ← 텍스트→음소 변환에 필요
# ----------------------------------------------------------
RUN mkdir -p /app/model /app/output \
    && echo "[모델 다운로드] vits-mimic3-ko_KO-kss_low ..." \
    && curl -fL \
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-mimic3-ko_KO-kss_low.tar.bz2" \
        -o /tmp/model.tar.bz2 \
    && tar -xjf /tmp/model.tar.bz2 -C /tmp/ \
    && cp -r /tmp/vits-mimic3-ko_KO-kss_low/. /app/model/ \
    && rm -rf /tmp/model.tar.bz2 /tmp/vits-mimic3-ko_KO-kss_low \
    && echo "[모델 다운로드 완료]" && ls -lh /app/model/

# 출력 WAV 볼륨
VOLUME ["/app/output"]

# ----------------------------------------------------------
# 환경 변수 (Render 대시보드 또는 docker run -e 로 오버라이드)
# ----------------------------------------------------------
ENV MODEL_DIR=/app/model \
    OUTPUT_WAV=/app/output/output.wav \
    TTS_TEXT="안녕하세요. 셰르파 온넥스 한국어 TTS 테스트입니다." \
    TTS_SID=0 \
    TTS_SPEED=1.0 \
    JAVA_OPTS=""

# ----------------------------------------------------------
# 실행 진입점
# -Djava.library.path=/app/libs : 네이티브 .so 로드 경로
# ----------------------------------------------------------
ENTRYPOINT ["sh", "-c", \
  "exec java $JAVA_OPTS \
    -Djava.library.path=/app/libs \
    -Dmodel.dir=$MODEL_DIR \
    -Doutput.wav=$OUTPUT_WAV \
    -Dtts.text=\"$TTS_TEXT\" \
    -Dtts.sid=$TTS_SID \
    -Dtts.speed=$TTS_SPEED \
    -jar /app/tts-poc.jar"]
