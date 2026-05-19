#!/usr/bin/env bash
# Piper TTS 훈련 환경 설정 스크립트 (macOS Apple Silicon)
# 실행: bash setup_mac.sh
set -euo pipefail

VENV_DIR="$(pwd)/.venv"
PIPER_DIR="$(pwd)/piper"

echo "=============================================="
echo " Piper TTS Training 환경 설정 (Apple Silicon)"
echo "=============================================="
echo ""

# --------------------------------------------------
# 1. 사전 요구사항 확인
# --------------------------------------------------
echo "[1/6] 사전 요구사항 확인..."

check_cmd() {
    if ! command -v "$1" &>/dev/null; then
        echo "  ✗ $1 없음 → brew install $2"
        MISSING=1
    else
        echo "  ✓ $1: $($1 --version 2>&1 | head -1)"
    fi
}
MISSING=0
check_cmd python3   "python@3.11"
check_cmd git       "git"
check_cmd brew      "(https://brew.sh)"
check_cmd espeak-ng "espeak-ng"

if [ "${MISSING}" -eq 1 ]; then
    echo ""
    echo "  아래 명령으로 누락된 패키지를 설치하세요:"
    echo "    brew install python@3.11 espeak-ng git"
    echo ""
    exit 1
fi

# Python 버전 확인 (3.10+ 필요)
PY_VER=$(python3 -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')")
PY_MAJOR=$(echo "$PY_VER" | cut -d. -f1)
PY_MINOR=$(echo "$PY_VER" | cut -d. -f2)
if [ "$PY_MAJOR" -lt 3 ] || { [ "$PY_MAJOR" -eq 3 ] && [ "$PY_MINOR" -lt 10 ]; }; then
    echo "  ✗ Python 3.10+ 필요 (현재: $PY_VER)"
    echo "    brew install python@3.11 && brew link python@3.11"
    exit 1
fi
echo "  ✓ Python $PY_VER (OK)"

# --------------------------------------------------
# 2. Piper 소스 클론
# --------------------------------------------------
echo ""
echo "[2/6] Piper 소스 다운로드..."
if [ -d "$PIPER_DIR" ]; then
    echo "  이미 존재: $PIPER_DIR (스킵)"
else
    git clone --depth 1 https://github.com/rhasspy/piper.git "$PIPER_DIR"
    echo "  ✓ 클론 완료"
fi

# --------------------------------------------------
# 3. Python 가상환경 생성
# --------------------------------------------------
echo ""
echo "[3/6] Python 가상환경 생성..."
if [ -d "$VENV_DIR" ]; then
    echo "  이미 존재: $VENV_DIR (스킵)"
else
    python3 -m venv "$VENV_DIR"
    echo "  ✓ 가상환경 생성됨: $VENV_DIR"
fi
source "$VENV_DIR/bin/activate"
pip install --upgrade pip wheel setuptools --quiet
echo "  ✓ pip $(pip --version | awk '{print $2}')"

# --------------------------------------------------
# 4. PyTorch 설치 (Apple Silicon MPS)
# --------------------------------------------------
echo ""
echo "[4/6] PyTorch 설치 (MPS 지원, Apple Silicon)..."
if python3 -c "import torch; torch.backends.mps.is_available()" 2>/dev/null; then
    echo "  ✓ PyTorch 이미 설치됨, MPS 사용 가능"
else
    pip install torch torchaudio --quiet
    echo "  ✓ PyTorch 설치 완료"
fi

# --------------------------------------------------
# 5. Piper-train 의존성 설치
# --------------------------------------------------
echo ""
echo "[5/6] Piper-train 의존성 설치..."
cd "$PIPER_DIR/src/python"
pip install -e . --quiet
echo "  ✓ piper-train 설치 완료"

# monotonic_align Cython 빌드 (훈련 속도에 중요)
echo ""
echo "  monotonic_align (Cython) 빌드 중..."
if bash build_monotonic_align.sh 2>&1 | tail -3; then
    echo "  ✓ monotonic_align 빌드 성공"
else
    echo "  ⚠ monotonic_align 빌드 실패 (훈련은 가능하나 느릴 수 있음)"
fi

# --------------------------------------------------
# 6. 환경 검증
# --------------------------------------------------
echo ""
echo "[6/6] 환경 검증..."
cd - > /dev/null
python3 verify_env.py

echo ""
echo "=============================================="
echo " 설정 완료! 다음 단계:"
echo ""
echo "  1. 가상환경 활성화:"
echo "     source .venv/bin/activate"
echo ""
echo "  2. 훈련 전 환경변수 설정:"
echo "     export PYTORCH_ENABLE_MPS_FALLBACK=1"
echo ""
echo "  3. 음성 데이터 준비 후 훈련:"
echo "     python3 -m piper_train.preprocess \\"
echo "       --language ko \\"
echo "       --input-dir ./recordings \\"
echo "       --output-dir ./dataset \\"
echo "       --dataset-format ljspeech"
echo ""
echo "  4. 훈련 실행:"
echo "     python3 -m piper_train.train \\"
echo "       --dataset-dir ./dataset \\"
echo "       --accelerator mps \\"
echo "       --devices 1 \\"
echo "       --batch-size 16 \\"
echo "       --validation-split 0.05"
echo "=============================================="
