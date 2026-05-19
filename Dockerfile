# ============================================================
# sherpa-onnx 한국어 TTS PoC - Dockerfile
#
# sherpa-onnx 는 Maven Central 미배포이므로, libs/ 디렉터리에
# 미리 다운로드된 JAR 와 .so 파일을 사용합니다.
#
# 빌드 전 준비 사항 (로컬 머신):
#   libs/sherpa-onnx-java17.jar
#   libs/libsherpa-onnx-jni.so
#   libs/libonnxruntime.so
#   (모두 GitHub Releases v1.12.0 에서 다운로드)
#
# 멀티 스테이지 빌드:
#   Stage 1 (builder): Gradle + JDK 17 → Fat JAR 생성
#   Stage 2 (runtime): JRE 17 (Ubuntu/Jammy) → 경량 실행 이미지
#
# ※ sherpa-onnx 네이티브 라이브러리는 glibc 에 의존하므로
#   반드시 Alpine 이 아닌 Ubuntu/Debian 계열 이미지를 사용합니다.
# ============================================================

# ----------------------------------------------------------
# Stage 1: 빌드 스테이지
#   - gradle:8.7-jdk17 은 Ubuntu(Jammy) 기반이므로 glibc 환경 보장
# ----------------------------------------------------------
FROM gradle:8.7-jdk17-jammy AS builder

LABEL stage="builder"

WORKDIR /build

# 의존성 캐시 레이어 최적화:
# build.gradle / settings.gradle 만 먼저 복사하여
# 소스가 변경되어도 의존성 레이어를 재사용합니다.
COPY build.gradle settings.gradle ./
COPY gradle/           gradle/

# libs/ 폴더의 로컬 JAR 복사 (sherpa-onnx Java API)
COPY libs/             libs/

# 의존성 확인 (캐시 활용)
RUN gradle dependencies --no-daemon --quiet 2>&1 || true

# 전체 소스 복사 후 Fat JAR 빌드
COPY src/ src/
RUN gradle shadowJar --no-daemon --no-build-cache \
    && echo "[빌드 완료] 생성된 JAR:" \
    && ls -lh build/libs/

# ----------------------------------------------------------
# Stage 2: 런타임 스테이지
#   - eclipse-temurin:17-jre-jammy (Ubuntu 22.04 LTS + JRE 17)
#   - 불필요한 JDK 도구 제외 → 이미지 크기 절감
# ----------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy AS runtime

LABEL maintainer="tts-poc"
LABEL description="sherpa-onnx 한국어 TTS PoC (Java 17 / Ubuntu Jammy)"

# 한국어 로케일 및 필수 런타임 라이브러리 설치
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        libstdc++6 \
        libgomp1 \
        locales \
    && locale-gen ko_KR.UTF-8 \
    && update-locale LANG=ko_KR.UTF-8 \
    && rm -rf /var/lib/apt/lists/*

ENV LANG=ko_KR.UTF-8 \
    LC_ALL=ko_KR.UTF-8

WORKDIR /app

# 빌드 스테이지에서 생성된 Fat JAR 복사
COPY --from=builder /build/build/libs/tts-poc.jar /app/tts-poc.jar

# 네이티브 .so 파일 복사 (libsherpa-onnx-jni.so, libonnxruntime.so)
COPY libs/*.so /app/libs/

# ----------------------------------------------------------
# 한국어 TTS 모델 디렉터리 마운트 포인트
#
#   방법 A. 빌드 시 COPY (이미지에 포함)
#     → 아래 COPY 줄 주석 해제
#
#   방법 B. 실행 시 볼륨 마운트 (기본 권장)
#     → docker run -v ./vits-mms-kor:/app/model ...
# ----------------------------------------------------------
# COPY ./vits-mms-kor /app/model

RUN mkdir -p /app/model /app/output

VOLUME ["/app/output"]

# ----------------------------------------------------------
# 실행 환경 변수 (docker run -e 로 오버라이드 가능)
# ----------------------------------------------------------
ENV MODEL_DIR=/app/model \
    OUTPUT_WAV=/app/output/output.wav \
    TTS_TEXT="안녕하세요. 셰르파 온넥스 한국어 TTS 테스트입니다." \
    TTS_SID=0 \
    TTS_SPEED=1.0 \
    JAVA_OPTS=""

# ----------------------------------------------------------
# 컨테이너 실행 진입점
#
# -Djava.library.path=/app/libs 로 네이티브 .so 로드 경로 지정
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
