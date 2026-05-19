#!/usr/bin/env python3
"""참조 오디오 자동 생성 — gTTS 우선, 실패 시 espeak-ng 사용"""
import io, os, sys, subprocess

TEXT = (
    "안녕하세요. 저는 한국어 음성 합성 서비스입니다. "
    "자연스러운 한국어 음성을 만들어 드리겠습니다. "
    "오늘도 좋은 하루 되세요."
)
OUT_WAV = os.path.join(os.path.dirname(__file__), "reference.wav")
OUT_TXT = os.path.join(os.path.dirname(__file__), "reference.txt")


def via_gtts():
    from gtts import gTTS
    from pydub import AudioSegment

    tts = gTTS(text=TEXT, lang="ko", slow=False)
    mp3_bio = io.BytesIO()
    tts.write_to_fp(mp3_bio)
    mp3_bio.seek(0)

    audio = AudioSegment.from_mp3(mp3_bio)
    audio = audio.set_frame_rate(24000).set_channels(1)
    audio.export(OUT_WAV, format="wav")
    duration = len(audio) / 1000
    print(f"✓ gTTS 생성: {OUT_WAV} ({duration:.1f}초)")


def via_espeak():
    subprocess.run(
        ["espeak-ng", "-v", "ko", "-s", "130", "-w", OUT_WAV, TEXT],
        check=True, capture_output=True,
    )
    # espeak 출력을 24kHz mono 로 변환
    subprocess.run(
        ["ffmpeg", "-y", "-i", OUT_WAV,
         "-ar", "24000", "-ac", "1", OUT_WAV + ".tmp.wav"],
        check=True, capture_output=True,
    )
    os.replace(OUT_WAV + ".tmp.wav", OUT_WAV)
    print(f"✓ espeak-ng 생성: {OUT_WAV}")


for attempt in [via_gtts, via_espeak]:
    try:
        attempt()
        break
    except Exception as e:
        print(f"  시도 실패 ({attempt.__name__}): {e}")
else:
    print("참조 오디오 생성 실패 — reference.wav 를 직접 제공하세요.", file=sys.stderr)
    sys.exit(1)

with open(OUT_TXT, "w", encoding="utf-8") as f:
    f.write(TEXT)
print(f"✓ 텍스트: {OUT_TXT}")
