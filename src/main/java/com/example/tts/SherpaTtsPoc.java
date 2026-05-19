package com.example.tts;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * sherpa-onnx v1.12.0 Java API를 이용한 한국어 TTS PoC.
 *
 * 실행 파라미터 (JVM 시스템 프로퍼티):
 *   -Djava.library.path=./libs   libsherpa-onnx-jni.so 경로 (필수)
 *   -Dmodel.dir=<경로>            VITS 모델 디렉터리 (기본값: ./vits-mms-kor)
 *   -Dtts.text=<텍스트>           합성할 한국어 텍스트
 *   -Doutput.wav=<경로>           출력 WAV 파일 경로 (기본값: ./output.wav)
 *   -Dtts.sid=<번호>              화자 ID (기본값: 0)
 *   -Dtts.speed=<배속>            말하기 속도 배율 (기본값: 1.0)
 */
public class SherpaTtsPoc {

    private static final String DEFAULT_MODEL_DIR  = "./vits-mms-kor";
    private static final String DEFAULT_OUTPUT_WAV = "./output.wav";
    private static final String DEFAULT_TEXT =
            "안녕하세요. 셰르파 온넥스 한국어 TTS 테스트입니다.";

    public static void main(String[] args) {

        // -------------------------------------------------------
        // 1) 실행 파라미터 로드
        // -------------------------------------------------------
        String modelDir  = System.getProperty("model.dir",  DEFAULT_MODEL_DIR);
        String outputWav = System.getProperty("output.wav", DEFAULT_OUTPUT_WAV);
        String text      = System.getProperty("tts.text",   DEFAULT_TEXT);
        int    sid       = Integer.parseInt(System.getProperty("tts.sid",   "0"));
        float  speed     = Float.parseFloat(System.getProperty("tts.speed", "1.0"));

        System.out.println("=================================================");
        System.out.println(" sherpa-onnx 한국어 TTS PoC (v1.12.0)");
        System.out.println("=================================================");
        System.out.println("[설정] 모델 디렉터리 : " + modelDir);
        System.out.println("[설정] 출력 파일     : " + outputWav);
        System.out.println("[설정] 합성 텍스트   : " + text);
        System.out.println("[설정] 화자 ID       : " + sid);
        System.out.println("[설정] 말하기 속도   : " + speed);
        System.out.println("-------------------------------------------------");

        // -------------------------------------------------------
        // 2) 모델 파일 경로 구성 및 존재 여부 검증
        //
        //    vits-mms-kor/
        //    ├── model.onnx    ← VITS ONNX 모델
        //    └── tokens.txt    ← 토큰 매핑 파일
        // -------------------------------------------------------
        Path modelDirPath = Paths.get(modelDir).toAbsolutePath();
        Path modelOnnx    = modelDirPath.resolve("model.onnx");
        Path tokensFile   = modelDirPath.resolve("tokens.txt");

        validateFile(modelOnnx,  "ONNX 모델 파일(model.onnx)");
        validateFile(tokensFile, "토큰 파일(tokens.txt)");

        // -------------------------------------------------------
        // 3) sherpa-onnx TTS 설정 구성
        //    실제 API: Builder 패턴에 setXxx() 메서드 사용
        // -------------------------------------------------------

        // VITS 모델 설정 (MMS 모델은 lexicon 불필요, 토큰 기반)
        OfflineTtsVitsModelConfig vitsConfig = new OfflineTtsVitsModelConfig.Builder()
                .setModel(modelOnnx.toString())
                .setTokens(tokensFile.toString())
                .setLexicon("")
                .setDataDir("")
                .setNoiseScale(0.667f)
                .setNoiseScaleW(0.8f)
                .setLengthScale(1.0f)
                .build();

        // 모델 전반 설정 (스레드 수, 추론 백엔드)
        OfflineTtsModelConfig modelConfig = new OfflineTtsModelConfig.Builder()
                .setVits(vitsConfig)
                .setNumThreads(2)
                .setDebug(false)
                .setProvider("cpu")
                .build();

        // TTS 최상위 설정
        OfflineTtsConfig ttsConfig = new OfflineTtsConfig.Builder()
                .setModel(modelConfig)
                .setRuleFsts("")
                .setMaxNumSentences(1)
                .build();

        // -------------------------------------------------------
        // 4) TTS 엔진 초기화 및 음성 합성
        // -------------------------------------------------------
        OfflineTts tts = null;
        try {
            System.out.println("[진행] TTS 엔진 초기화 중...");
            tts = new OfflineTts(ttsConfig);
            System.out.println("[완료] TTS 엔진 초기화 성공.");

            long startMs = System.currentTimeMillis();

            System.out.println("[진행] 음성 합성 중...");
            // generate(text, speakerId, speed)
            GeneratedAudio audio = tts.generate(text, sid, speed);

            long elapsedMs = System.currentTimeMillis() - startMs;

            // -------------------------------------------------------
            // 5) WAV 파일 저장
            // -------------------------------------------------------
            Path outputPath = Paths.get(outputWav).toAbsolutePath();
            Files.createDirectories(outputPath.getParent());

            boolean saved = audio.save(outputPath.toString());
            if (!saved) {
                throw new RuntimeException("WAV 파일 저장 실패: " + outputPath);
            }

            System.out.println("-------------------------------------------------");
            System.out.println("[완료] 음성 합성 성공!");
            System.out.printf ("[결과] 출력 파일   : %s%n", outputPath);
            System.out.printf ("[결과] 샘플 레이트 : %d Hz%n", audio.getSampleRate());
            System.out.printf ("[결과] 소요 시간   : %d ms%n", elapsedMs);
            System.out.println("=================================================");

        } catch (Exception e) {
            System.err.println("[오류] TTS 처리 중 예외 발생: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);

        } finally {
            // 6) 네이티브 리소스 해제 (메모리 누수 방지)
            if (tts != null) {
                tts.release();
                System.out.println("[정리] TTS 엔진 리소스 해제 완료.");
            }
        }
    }

    private static void validateFile(Path path, String description) {
        if (!Files.exists(path)) {
            System.err.printf("[오류] %s 을(를) 찾을 수 없습니다: %s%n", description, path);
            System.err.println("       -Dmodel.dir 경로를 확인하세요.");
            System.exit(1);
        }
        System.out.printf("[확인] %-30s → %s%n", description, path);
    }
}
