package com.example.tts;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * sherpa-onnx 라이브러리를 이용한 한국어 TTS PoC 애플리케이션.
 *
 * 실행 파라미터 (모두 JVM 시스템 프로퍼티로 주입):
 *   -Dmodel.dir=<경로>    VITS 모델 디렉터리 (기본값: ./vits-mms-kor)
 *   -Dtts.text=<텍스트>   합성할 한국어 텍스트
 *   -Doutput.wav=<경로>   출력 WAV 파일 경로 (기본값: ./output.wav)
 *   -Dtts.sid=<번호>      화자 ID (기본값: 0)
 *   -Dtts.speed=<배속>    말하기 속도 배율 (기본값: 1.0)
 */
public class SherpaTtsPoc {

    // -------------------------------------------------------
    // 기본값 상수
    // -------------------------------------------------------
    private static final String DEFAULT_MODEL_DIR  = "./vits-mms-kor";
    private static final String DEFAULT_OUTPUT_WAV = "./output.wav";
    private static final String DEFAULT_TEXT =
            "안녕하세요. 셰르파 온넥스 한국어 TTS 테스트입니다.";
    private static final int    DEFAULT_SID   = 0;
    private static final float  DEFAULT_SPEED = 1.0f;

    public static void main(String[] args) {

        // -------------------------------------------------------
        // 1) 실행 파라미터 로드
        // -------------------------------------------------------
        String modelDir   = System.getProperty("model.dir",  DEFAULT_MODEL_DIR);
        String outputWav  = System.getProperty("output.wav", DEFAULT_OUTPUT_WAV);
        String text       = System.getProperty("tts.text",   DEFAULT_TEXT);
        int    speakerId  = Integer.parseInt(System.getProperty("tts.sid",   String.valueOf(DEFAULT_SID)));
        float  speed      = Float.parseFloat(System.getProperty("tts.speed", String.valueOf(DEFAULT_SPEED)));

        System.out.println("=================================================");
        System.out.println(" sherpa-onnx 한국어 TTS PoC");
        System.out.println("=================================================");
        System.out.println("[설정] 모델 디렉터리 : " + modelDir);
        System.out.println("[설정] 출력 파일     : " + outputWav);
        System.out.println("[설정] 합성 텍스트   : " + text);
        System.out.println("[설정] 화자 ID       : " + speakerId);
        System.out.println("[설정] 말하기 속도   : " + speed);
        System.out.println("-------------------------------------------------");

        // -------------------------------------------------------
        // 2) 모델 파일 경로 구성 및 존재 여부 검증
        //
        //    vits-mms-kor/
        //    ├── model.onnx       ← VITS ONNX 모델
        //    └── tokens.txt       ← 토큰(문자→ID) 매핑 파일
        // -------------------------------------------------------
        Path modelDirPath  = Paths.get(modelDir).toAbsolutePath();
        Path modelOnnx     = modelDirPath.resolve("model.onnx");
        Path tokensFile    = modelDirPath.resolve("tokens.txt");

        validateFile(modelOnnx,  "ONNX 모델 파일(model.onnx)");
        validateFile(tokensFile, "토큰 파일(tokens.txt)");

        // -------------------------------------------------------
        // 3) sherpa-onnx TTS 설정 구성
        // -------------------------------------------------------

        // VITS 모델 설정
        OfflineTtsVitsModelConfig vitsConfig = OfflineTtsVitsModelConfig.builder()
                .model(modelOnnx.toString())   // ONNX 모델 경로
                .lexicon("")                   // MMS 모델은 lexicon 불필요 (토큰 기반)
                .tokens(tokensFile.toString()) // 토큰 파일 경로
                .dataDir("")                   // espeak-ng 데이터 디렉터리 (미사용 시 공란)
                .noiseScale(0.667f)            // 음성 다양성 노이즈 스케일
                .noiseScaleW(0.8f)             // 길이 다양성 노이즈 스케일
                .lengthScale(1.0f)             // 길이 스케일 (speed 와 별개)
                .build();

        // 모델 전반 설정 (스레드 수, 추론 백엔드 등)
        OfflineTtsModelConfig modelConfig = OfflineTtsModelConfig.builder()
                .vits(vitsConfig)
                .numThreads(2)    // 추론에 사용할 CPU 스레드 수
                .debug(false)     // 디버그 로그 비활성화
                .provider("cpu")  // 추론 백엔드: cpu / cuda / coreml
                .build();

        // TTS 최상위 설정
        OfflineTtsConfig ttsConfig = OfflineTtsConfig.builder()
                .model(modelConfig)
                .ruleFsts("")        // 텍스트 정규화 FST 규칙 (선택 사항)
                .maxNumSentences(1)  // 한 번에 처리할 최대 문장 수
                .build();

        // -------------------------------------------------------
        // 4) TTS 엔진 초기화 및 음성 합성
        // -------------------------------------------------------
        OfflineTts tts = null;
        try {
            System.out.println("[진행] TTS 엔진을 초기화 중...");
            tts = new OfflineTts(ttsConfig);
            System.out.println("[완료] TTS 엔진 초기화 성공.");

            // 음성 합성 시작 시각 기록
            long startMs = System.currentTimeMillis();

            System.out.println("[진행] 음성 합성 중...");
            GeneratedAudio audio = tts.generate(text, speakerId, speed);

            long elapsedMs = System.currentTimeMillis() - startMs;

            // -------------------------------------------------------
            // 5) WAV 파일 저장
            // -------------------------------------------------------
            // 출력 디렉터리가 없으면 자동 생성
            Path outputPath = Paths.get(outputWav).toAbsolutePath();
            Files.createDirectories(outputPath.getParent());

            boolean saved = audio.save(outputPath.toString());
            if (!saved) {
                throw new RuntimeException("WAV 파일 저장 실패: " + outputPath);
            }

            System.out.println("-------------------------------------------------");
            System.out.println("[완료] 음성 합성 성공!");
            System.out.printf ("[결과] 출력 파일  : %s%n", outputPath);
            System.out.printf ("[결과] 샘플 레이트 : %d Hz%n", audio.getSampleRate());
            System.out.printf ("[결과] 소요 시간   : %d ms%n", elapsedMs);
            System.out.println("=================================================");

        } catch (Exception e) {
            System.err.println("[오류] TTS 처리 중 예외 발생: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);

        } finally {
            // -------------------------------------------------------
            // 6) 네이티브 리소스 반드시 해제 (메모리 누수 방지)
            // -------------------------------------------------------
            if (tts != null) {
                tts.release();
                System.out.println("[정리] TTS 엔진 리소스 해제 완료.");
            }
        }
    }

    /**
     * 파일 존재 여부를 검증하고, 없으면 명확한 오류 메시지와 함께 종료합니다.
     */
    private static void validateFile(Path path, String description) {
        if (!Files.exists(path)) {
            System.err.printf("[오류] %s 을(를) 찾을 수 없습니다: %s%n", description, path);
            System.err.println("       모델 디렉터리(-Dmodel.dir) 경로를 확인하세요.");
            System.exit(1);
        }
        System.out.printf("[확인] %-30s → %s%n", description, path);
    }
}
