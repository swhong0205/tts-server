#!/bin/sh
# ==============================================================
# sherpa-onnx TTS 스타트업 스크립트
#
# 컨테이너 시작 시 /app/model 에 모델 파일이 없으면
# GitHub Releases 에서 한국어 VITS 모델을 자동 다운로드합니다.
#
# 모델: vits-mimic3-ko_KO-kss_low (sherpa-onnx 공식 한국어 모델)
# 출처: https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models
# ==============================================================

set -e

MODEL_DIR="${MODEL_DIR:-/app/model}"
MODEL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-mimic3-ko_KO-kss_low.tar.bz2"
MODEL_FOLDER="vits-mimic3-ko_KO-kss_low"

# 이미 모델이 있으면 다운로드 생략
if [ -f "${MODEL_DIR}/ko_KO-kss_low.onnx" ]; then
    echo "[스타트업] 모델 파일이 이미 존재합니다. 다운로드를 생략합니다."
else
    echo "[스타트업] 한국어 TTS 모델을 다운로드합니다..."
    echo "[스타트업] URL: ${MODEL_URL}"

    mkdir -p "${MODEL_DIR}" /tmp/model-dl

    # 다운로드
    curl -fL --progress-bar "${MODEL_URL}" -o /tmp/model-dl/model.tar.bz2

    # 압축 해제
    echo "[스타트업] 압축 해제 중..."
    tar -xjf /tmp/model-dl/model.tar.bz2 -C /tmp/model-dl/

    # 모델 파일을 /app/model 로 이동 (디렉터리 내용만)
    cp -r /tmp/model-dl/${MODEL_FOLDER}/. "${MODEL_DIR}/"

    # 임시 파일 정리
    rm -rf /tmp/model-dl

    echo "[스타트업] 모델 다운로드 완료."
    ls -lh "${MODEL_DIR}/"
fi

# TTS 애플리케이션 실행
# -Djava.library.path: 네이티브 .so 파일 위치
# 나머지 파라미터는 환경 변수로 주입
echo "[스타트업] TTS 애플리케이션을 시작합니다..."
exec java ${JAVA_OPTS} \
    -Djava.library.path=/app/libs \
    -Dmodel.dir="${MODEL_DIR}" \
    -Doutput.wav="${OUTPUT_WAV:-/app/output/output.wav}" \
    -Dtts.text="${TTS_TEXT:-안녕하세요. 셰르파 온넥스 한국어 TTS 테스트입니다.}" \
    -Dtts.sid="${TTS_SID:-0}" \
    -Dtts.speed="${TTS_SPEED:-1.0}" \
    -jar /app/tts-poc.jar
