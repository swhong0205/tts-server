# Piper TTS 훈련 환경 (Apple Silicon M4 Pro)

## 사전 요구사항 설치

```bash
# Homebrew 패키지
brew install python@3.11 espeak-ng git

# python3가 3.11을 가리키도록 (필요 시)
brew link --force python@3.11
```

## 환경 설정 (한 번만)

```bash
cd piper-training/

# 자동 설정 (클론 → 가상환경 → PyTorch → piper-train 설치)
bash setup_mac.sh
```

완료되면 다음 항목이 설치됩니다:
- PyTorch (MPS 지원, Apple Silicon 최적화)
- torchaudio
- piper-train + piper-phonemize
- monotonic_align Cython 빌드 (훈련 속도 향상)

## 환경 검증만 별도 실행

```bash
source .venv/bin/activate
python3 verify_env.py
```

정상 출력 예시:
```
── PyTorch / MPS ────────────────────────────
  ✓ torch 설치: 2.x.x
  ✓ MPS 사용 가능: 사용 가능
  ✓ MPS 텐서 연산: 3×3 행렬 연산 OK
  ✓ MPS 행렬 성능: 1024×1024 matmul: X.X ms/iter

── espeak-ng (한국어 음소변환) ──────────────
  ✓ espeak-ng 설치: eSpeak NG text-to-speech: X.X.X
  ✓ 한국어 음소 변환: IPA: anbʲʌŋhasejo
```

## 훈련 흐름 (음성 데이터 준비 후)

### 1. 데이터 형식

LJSpeech 형식 권장:
```
recordings/
  wavs/
    001.wav    # 22050Hz, mono, 16-bit PCM
    002.wav
    ...
  metadata.csv   # 파일명|전사텍스트|전사텍스트
```

`metadata.csv` 예시:
```
001|안녕하세요 반갑습니다|안녕하세요 반갑습니다
002|오늘 날씨가 좋네요|오늘 날씨가 좋네요
```

최소 권장 데이터량: **1시간** (약 1,000~3,000 문장)

### 2. 전처리

```bash
export PYTORCH_ENABLE_MPS_FALLBACK=1
source .venv/bin/activate

python3 -m piper_train.preprocess \
  --language ko \
  --input-dir ./recordings \
  --output-dir ./dataset \
  --dataset-format ljspeech \
  --single-speaker
```

### 3. 훈련

```bash
python3 -m piper_train.train \
  --dataset-dir ./dataset \
  --accelerator mps \
  --devices 1 \
  --batch-size 16 \
  --validation-split 0.05 \
  --num-workers 4
```

**M4 Pro 48GB 예상 훈련 시간:**
| 데이터 | 에폭 수 | 예상 시간 |
|--------|---------|----------|
| 1시간  | 1,000   | ~2-3시간  |
| 1시간  | 10,000  | ~20-30시간 |

### 4. 모델 내보내기 (ONNX)

```bash
python3 -m piper_train.export_onnx \
  --checkpoint ./training/lightning_logs/version_0/checkpoints/last.ckpt \
  --output ./my-voice.onnx
```

내보낸 `.onnx` 파일을 sherpa-onnx 서버의 모델로 바로 사용 가능합니다.

## 주의사항

- `PYTORCH_ENABLE_MPS_FALLBACK=1` 항상 설정 (MPS 미구현 연산 CPU 폴백)
- 배치 크기는 RAM 사용량에 따라 조절 (OOM 시 `--batch-size 8` 시도)
- 체크포인트는 `./training/` 에 자동 저장 (주기적으로 백업 권장)
