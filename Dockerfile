# ============================================================
# sherpa-onnx 한국어 TTS PoC - Dockerfile
#
# 멀티 스테이지 빌드:
#   Stage 1 (builder): Gradle + JDK 17 → Fat JAR 생성
#   Stage 2 (runtime): JRE 17 (Ubuntu/Jammy) → 경량 실행 이미지
#
# 모델 자동 다운로드:
#   컨테이너 시작 시 entrypoint.sh 가 /app/model 에 모델이 없으면
#   GitHub Releases 에서 vits-mimic3-ko_KO-kss_low 를 자동 다운로드합니다.
#   (Render Persistent Disk 마운트 시 재시작 후 재다운로드 불필요)
# ============================================================

# ----------------------------------------------------------
# Stage 1: 빌드 스테이지
# ----------------------------------------------------------
FROM gradle:8.7-jdk17-jammy AS builder

LABEL stage="builder"

WORKDIR /build

COPY build.gradle settings.gradle ./
COPY gradle/           gradle/
COPY libs/             libs/

RUN gradle dependencies --no-daemon --quiet 2>&1 || true

COPY src/ src/
RUN gradle shadowJar --no-daemon --no-build-cache \
    && echo "[빌드 완료] 생성된 JAR:" \
    && ls -lh build/libs/

# ----------------------------------------------------------
# Stage 2: 런타임 스테이지
#   Ubuntu 22.04 Jammy 기반 (glibc 필수 - sherpa-onnx .so 의존)
# ----------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy AS runtime

LABEL maintainer="tts-poc"
LABEL description="sherpa-onnx 한국어 TTS PoC (Java 17 / Ubuntu Jammy)"

# 한국어 로케일 + sherpa-onnx 네이티브 라이브러리 의존 패키지
# curl: 모델 자동 다운로드용
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

# Fat JAR 복사
COPY --from=builder /build/build/libs/tts-poc.jar /app/tts-poc.jar

# 네이티브 .so 파일 복사
COPY libs/*.so /app/libs/

# 스타트업 스크립트 복사 및 실행 권한 부여
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

# 모델 마운트 포인트 및 출력 디렉터리 생성
# - /app/model : Render Persistent Disk 마운트 포인트 (없으면 자동 다운로드)
# - /app/output: WAV 출력 결과물
RUN mkdir -p /app/model /app/output

VOLUME ["/app/model", "/app/output"]

# ----------------------------------------------------------
# 실행 환경 변수 (docker run -e 또는 Render 환경 변수로 오버라이드)
# ----------------------------------------------------------
ENV MODEL_DIR=/app/model \
    OUTPUT_WAV=/app/output/output.wav \
    TTS_TEXT="안녕하세요. 셰르파 온넥스 한국어 TTS 테스트입니다." \
    TTS_SID=0 \
    TTS_SPEED=1.0 \
    JAVA_OPTS=""

# entrypoint.sh 가 모델 다운로드 후 java 를 exec 합니다
ENTRYPOINT ["/app/entrypoint.sh"]
