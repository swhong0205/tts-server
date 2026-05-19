package com.example.tts;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * sherpa-onnx v1.12.0 Java API를 이용한 한국어 TTS PoC.
 *
 * 지원 모델:
 *   - vits-mimic3-ko_KO-kss_low (기본, entrypoint.sh 자동 다운로드)
 *   - vits-mms-kor 등 다른 VITS 모델도 model.dir 변경으로 사용 가능
 *
 * JVM 시스템 프로퍼티:
 *   -Djava.library.path=./libs   libsherpa-onnx-jni.so 경로 (필수)
 *   -Dmodel.dir=<경로>            모델 디렉터리 (기본값: ./vits-mms-kor)
 *   -Dmodel.onnx=<파일명>         ONNX 파일명 지정 (생략 시 자동 탐색)
 *   -Dtts.text=<텍스트>           합성할 한국어 텍스트
 *   -Doutput.wav=<경로>           출력 WAV 파일 경로
 *   -Dtts.sid=<번호>              화자 ID (기본값: 0)
 *   -Dtts.speed=<배속>            말하기 속도 배율 (기본값: 1.0)
 */
public class SherpaTtsPoc {

    private static final String DEFAULT_MODEL_DIR  = "./vits-mms-kor";
    private static final String DEFAULT_OUTPUT_WAV = "./output.wav";
    private static final String DEFAULT_TEXT =
            "안녕하세요. 셰르파 온넥스 한국어 TTS 테스트입니다.";

    public static void main(String[] args) throws IOException {

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
        // 2) 모델 파일 경로 자동 탐색
        //
        //    -Dmodel.onnx 로 파일명을 직접 지정하거나,
        //    생략 시 modelDir 에서 첫 번째 .onnx 파일을 자동 선택
        //
        //    지원 모델 예시:
        //      vits-mimic3-ko_KO-kss_low/  → ko_KO-kss_low.onnx
        //      vits-mms-kor/               → model.onnx
        // -------------------------------------------------------
        Path modelDirPath = Paths.get(modelDir).toAbsolutePath();
        if (!Files.isDirectory(modelDirPath)) {
            System.err.println("[오류] 모델 디렉터리가 존재하지 않습니다: " + modelDirPath);
            System.err.println("       -Dmodel.dir 경로를 확인하거나 entrypoint.sh 를 통해 실행하세요.");
            System.exit(1);
        }

        // ONNX 파일: 직접 지정 우선, 없으면 디렉터리에서 자동 탐색
        Path onnxFile = resolveOnnxFile(modelDirPath);
        Path tokensFile = modelDirPath.resolve("tokens.txt");

        validateFile(onnxFile,   "ONNX 모델 파일");
        validateFile(tokensFile, "토큰 파일(tokens.txt)");

        // espeak-ng-data 디렉터리 (mimic3 계열 모델에서 필요)
        Path espeakDataDir = modelDirPath.resolve("espeak-ng-data");
        String dataDir = Files.isDirectory(espeakDataDir) ? espeakDataDir.toString() : "";
        System.out.printf("[설정] espeak-ng-data : %s%n",
                dataDir.isEmpty() ? "(사용 안 함)" : dataDir);

        // -------------------------------------------------------
        // 3) sherpa-onnx TTS 설정 구성
        // -------------------------------------------------------
        OfflineTtsVitsModelConfig vitsConfig = new OfflineTtsVitsModelConfig.Builder()
                .setModel(onnxFile.toString())
                .setTokens(tokensFile.toString())
                .setLexicon("")
                .setDataDir(dataDir)
                .setNoiseScale(0.667f)
                .setNoiseScaleW(0.8f)
                .setLengthScale(1.0f)
                .build();

        OfflineTtsModelConfig modelConfig = new OfflineTtsModelConfig.Builder()
                .setVits(vitsConfig)
                .setNumThreads(2)
                .setDebug(false)
                .setProvider("cpu")
                .build();

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

            GeneratedAudio audio = tts.generate(text, sid, speed);
            long elapsedMs = System.currentTimeMillis() - startMs;

            // -------------------------------------------------------
            // 5) WAV 파일 저장
            // -------------------------------------------------------
            Path outputPath = Paths.get(outputWav).toAbsolutePath();
            Files.createDirectories(outputPath.getParent());

            if (!audio.save(outputPath.toString())) {
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
            // 6) 네이티브 리소스 해제
            if (tts != null) {
                tts.release();
                System.out.println("[정리] TTS 엔진 리소스 해제 완료.");
            }
        }
    }

    /**
     * 모델 디렉터리에서 ONNX 파일을 찾습니다.
     * -Dmodel.onnx 로 지정된 파일명을 우선 사용하고,
     * 없으면 디렉터리 내 첫 번째 .onnx 파일을 반환합니다.
     */
    private static Path resolveOnnxFile(Path modelDirPath) throws IOException {
        String specifiedName = System.getProperty("model.onnx");
        if (specifiedName != null && !specifiedName.isEmpty()) {
            return modelDirPath.resolve(specifiedName);
        }
        Optional<Path> found = Files.list(modelDirPath)
                .filter(p -> p.toString().endsWith(".onnx"))
                .findFirst();
        if (found.isEmpty()) {
            System.err.println("[오류] 모델 디렉터리에 .onnx 파일이 없습니다: " + modelDirPath);
            System.exit(1);
        }
        return found.get();
    }

    private static void validateFile(Path path, String description) {
        if (!Files.exists(path)) {
            System.err.printf("[오류] %s 을(를) 찾을 수 없습니다: %s%n", description, path);
            System.exit(1);
        }
        System.out.printf("[확인] %-25s → %s%n", description, path);
    }
}
