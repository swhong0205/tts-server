# ============================================================
# sherpa-onnx 한국어 TTS PoC - Dockerfile
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
# 소스 변경 전에 build.gradle / settings.gradle 만 먼저 복사하여
# 소스가 바뀌어도 의존성 다운로드 레이어를 재사용합니다.
COPY build.gradle settings.gradle ./
COPY gradle/           gradle/

# 의존성만 미리 다운로드 (캐시 활용)
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
# libstdc++6, libgomp1: sherpa-onnx C++ 네이티브 라이브러리 의존성
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

# 애플리케이션 작업 디렉터리
WORKDIR /app

# 빌드 스테이지에서 생성된 Fat JAR 복사
COPY --from=builder /build/build/libs/tts-poc.jar /app/tts-poc.jar

# ----------------------------------------------------------
# 한국어 TTS 모델 디렉터리 마운트 포인트
#
# 두 가지 방법으로 모델을 제공할 수 있습니다:
#
#   방법 A. Dockerfile 에서 직접 COPY (이미지에 포함)
#     → 이미지 크기가 커지지만 독립 실행 가능
#     → COPY ./vits-mms-kor /app/model 주석 해제 후 사용
#
#   방법 B. docker run 시 볼륨 마운트 (기본 권장)
#     → docker run -v ./vits-mms-kor:/app/model ...
#     → 모델 업데이트 시 이미지 재빌드 불필요
#
# 아래 COPY 줄은 방법 A 사용 시 주석 해제하세요.
# ----------------------------------------------------------
# COPY ./vits-mms-kor /app/model

# 모델 마운트 포인트 디렉터리 생성
RUN mkdir -p /app/model /app/output

# 출력 WAV 를 컨테이너 밖에서 확인할 수 있도록 볼륨 선언
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
# 환경 변수를 JVM 시스템 프로퍼티로 전달합니다.
# JAVA_OPTS 로 추가 JVM 옵션(힙 크기 등)을 주입할 수 있습니다.
# ----------------------------------------------------------
ENTRYPOINT ["sh", "-c", \
  "exec java $JAVA_OPTS \
    -Dmodel.dir=$MODEL_DIR \
    -Doutput.wav=$OUTPUT_WAV \
    -Dtts.text=\"$TTS_TEXT\" \
    -Dtts.sid=$TTS_SID \
    -Dtts.speed=$TTS_SPEED \
    -jar /app/tts-poc.jar"]
