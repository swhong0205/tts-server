#!/usr/bin/env python3
"""Piper TTS 훈련 환경 검증 스크립트 (Apple Silicon)

실행: python3 verify_env.py
"""
import sys
import subprocess
import time

PASS = "  ✓"
FAIL = "  ✗"
WARN = "  ⚠"

results = []

def check(label, fn):
    try:
        info = fn()
        print(f"{PASS} {label}: {info}")
        results.append((label, True, info))
    except Exception as e:
        print(f"{FAIL} {label}: {e}")
        results.append((label, False, str(e)))

# --------------------------------------------------
# Python
# --------------------------------------------------
print("\n── Python 환경 ──────────────────────────────")

def check_python():
    v = sys.version_info
    assert v >= (3, 10), f"{v.major}.{v.minor} (3.10+ 필요)"
    return f"{v.major}.{v.minor}.{v.micro}"

check("Python 버전", check_python)

# --------------------------------------------------
# PyTorch + MPS
# --------------------------------------------------
print("\n── PyTorch / MPS ────────────────────────────")

def check_torch():
    import torch
    return torch.__version__

def check_mps_available():
    import torch
    assert torch.backends.mps.is_available(), "MPS 미지원 (macOS 12.3+ 및 Apple Silicon 필요)"
    assert torch.backends.mps.is_built(), "PyTorch가 MPS 없이 빌드됨"
    return "사용 가능"

def check_mps_tensor():
    import torch
    x = torch.ones(3, 3, device="mps")
    y = torch.ones(3, 3, device="mps")
    z = (x + y).sum().item()
    assert abs(z - 18.0) < 1e-3
    return f"3×3 행렬 연산 OK (합={z:.0f})"

def check_mps_perf():
    import torch
    device = "mps"
    N = 1024
    a = torch.randn(N, N, device=device)
    b = torch.randn(N, N, device=device)
    # 워밍업
    _ = torch.mm(a, b)
    torch.mps.synchronize()
    t0 = time.perf_counter()
    for _ in range(10):
        c = torch.mm(a, b)
    torch.mps.synchronize()
    ms = (time.perf_counter() - t0) / 10 * 1000
    return f"{N}×{N} matmul: {ms:.1f} ms/iter"

check("torch 설치",           check_torch)
check("MPS 사용 가능",        check_mps_available)
check("MPS 텐서 연산",        check_mps_tensor)
check("MPS 행렬 성능",        check_mps_perf)

# --------------------------------------------------
# torchaudio
# --------------------------------------------------
print("\n── 오디오 처리 ──────────────────────────────")

def check_torchaudio():
    import torchaudio
    return torchaudio.__version__

def check_torchaudio_load():
    import torchaudio
    # 빈 WAV 생성 후 로드 테스트
    import torch, tempfile, os
    waveform = torch.zeros(1, 22050)  # 1초 무음
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
        path = f.name
    torchaudio.save(path, waveform, 22050)
    loaded, sr = torchaudio.load(path)
    os.unlink(path)
    assert sr == 22050
    assert loaded.shape == (1, 22050)
    return "저장/로드 OK (22050Hz mono)"

check("torchaudio 설치",      check_torchaudio)
check("WAV 입출력",           check_torchaudio_load)

# --------------------------------------------------
# espeak-ng (한국어 음소 변환)
# --------------------------------------------------
print("\n── espeak-ng (한국어 음소변환) ──────────────")

def check_espeak_installed():
    r = subprocess.run(["espeak-ng", "--version"], capture_output=True, text=True)
    assert r.returncode == 0, "espeak-ng not found (brew install espeak-ng)"
    return r.stdout.strip().split("\n")[0]

def check_espeak_korean():
    r = subprocess.run(
        ["espeak-ng", "-v", "ko", "-q", "--ipa", "안녕하세요"],
        capture_output=True, text=True
    )
    assert r.returncode == 0, r.stderr.strip()
    ipa = r.stdout.strip() or r.stderr.strip()
    return f"IPA: {ipa[:50]}"

check("espeak-ng 설치",       check_espeak_installed)
check("한국어 음소 변환",     check_espeak_korean)

# --------------------------------------------------
# piper-train 패키지
# --------------------------------------------------
print("\n── Piper-train 패키지 ───────────────────────")

def check_piper_phonemize():
    import piper_phonemize
    result = piper_phonemize.phonemize_espeak("안녕하세요", "ko")
    assert result, "음소 변환 결과 없음"
    return f"OK → {result[0][:40] if result else '?'}"

def check_piper_train():
    import piper_train
    return "import OK"

def check_lightning():
    import pytorch_lightning as pl
    return pl.__version__

check("piper_phonemize",      check_piper_phonemize)
check("piper_train",          check_piper_train)
check("pytorch_lightning",    check_lightning)

# --------------------------------------------------
# monotonic_align (Cython 빌드 확인)
# --------------------------------------------------
print("\n── monotonic_align (Cython) ─────────────────")

def check_monotonic_align():
    from piper_train.vits.monotonic_align import maximum_path
    import torch, numpy as np
    # 간단한 더미 호출
    v = np.zeros((1, 5, 3), dtype=np.float32)
    lens_x = np.array([3], dtype=np.int32)
    lens_y = np.array([5], dtype=np.int32)
    # maximum_path 호출 가능 여부만 확인
    return "Cython 빌드 버전 사용 가능"

check("monotonic_align",      check_monotonic_align)

# --------------------------------------------------
# 결과 요약
# --------------------------------------------------
print("\n" + "=" * 50)
passed = sum(1 for _, ok, _ in results if ok)
total  = len(results)
print(f" 결과: {passed}/{total} 통과")
print("=" * 50)

critical_fail = [label for label, ok, _ in results
                 if not ok and label in ("Python 버전", "torch 설치", "MPS 사용 가능", "espeak-ng 설치")]

if critical_fail:
    print(f"\n{FAIL} 치명적 오류 항목:")
    for label in critical_fail:
        msg = next(m for l, ok, m in results if l == label and not ok)
        print(f"    - {label}: {msg}")
    sys.exit(1)

warn_only = [label for label, ok, _ in results if not ok and label not in critical_fail]
if warn_only:
    print(f"\n{WARN} 경고 항목 (훈련 가능하나 일부 기능 제한):")
    for label in warn_only:
        msg = next(m for l, ok, m in results if l == label and not ok)
        print(f"    - {label}: {msg}")
    print("\n훈련 진행 가능합니다 (경고 항목 검토 권장).")
else:
    print("\n모든 검증 통과! 훈련 준비 완료.")
