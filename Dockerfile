# ============================================================
# sherpa-onnx 한국어 TTS PoC - Dockerfile
#
# 멀티 스테이지 빌드:
#   Stage 1 (builder): Gradle + JDK 17 → Fat JAR 생성
#   Stage 2 (runtime): JRE 17 (Ubuntu/Jammy) + 모델 포함
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

ARG TARGETARCH

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        libstdc++6 \
        libgomp1 \
        locales \
        curl \
        bzip2 \
    && locale-gen ko_KR.UTF-8 \
    && update-locale LANG=ko_KR.UTF-8 \
    && rm -rf /var/lib/apt/lists/*

ENV LANG=ko_KR.UTF-8 \
    LC_ALL=ko_KR.UTF-8

WORKDIR /app

COPY --from=builder /build/build/libs/tts-poc.jar /app/tts-poc.jar

# 아키텍처에 맞는 JNI .so 다운로드 (amd64 → x64, arm64 → aarch64)
RUN case "$TARGETARCH" in \
      arm64) ARCH=aarch64 ;; \
      *)     ARCH=x64 ;; \
    esac \
    && mkdir -p /app/libs \
    && curl -fL \
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.2/sherpa-onnx-v1.13.2-linux-${ARCH}-jni.tar.bz2" \
        -o /tmp/jni.tar.bz2 \
    && tar -xjf /tmp/jni.tar.bz2 -C /tmp/ \
    && cp /tmp/sherpa-onnx-v1.13.2-linux-${ARCH}-jni/lib/*.so /app/libs/ \
    && rm -rf /tmp/jni.tar.bz2 /tmp/sherpa-onnx-v1.13.2-linux-${ARCH}-jni

RUN mkdir -p /app/model /app/output \
    && curl -fL \
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2" \
        -o /tmp/model.tar.bz2 \
    && tar -xjf /tmp/model.tar.bz2 -C /tmp/ \
    && cp -r /tmp/sherpa-onnx-supertonic-3-tts-int8-2026-05-11/. /app/model/ \
    && rm -rf /tmp/model.tar.bz2 /tmp/sherpa-onnx-supertonic-3-tts-int8-2026-05-11

VOLUME ["/app/output"]

ENV MODEL_DIR=/app/model \
    PORT=8080 \
    JAVA_OPTS=""

EXPOSE 8080
ENTRYPOINT ["sh", "-c", \
  "exec java $JAVA_OPTS \
    -Djava.library.path=/app/libs \
    -Dmodel.dir=$MODEL_DIR \
    -jar /app/tts-poc.jar"]
