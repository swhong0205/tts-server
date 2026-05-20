package com.example.tts;

import com.k2fsa.sherpa.onnx.*;
import com.sun.net.httpserver.*;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * sherpa-onnx 한국어 TTS HTTP 서버
 *
 * 엔드포인트:
 *   GET  /          → 웹 UI (텍스트 입력 → 음성 재생)
 *   GET  /health    → {"status":"ok"}
 *   POST /tts       → WAV 파일 (audio/wav)
 *
 * POST /tts 요청 바디 (application/json):
 *   {"text":"합성할 텍스트", "speed":1.0, "sid":0}
 */
public class SherpaTtsPoc {

    private static final String DEFAULT_MODEL_DIR = "/app/model";

    private static final int POOL_SIZE = Integer.parseInt(
            System.getenv().getOrDefault("TTS_POOL_SIZE", "2"));
    private static final ArrayBlockingQueue<OfflineTts> ttsPool = new ArrayBlockingQueue<>(POOL_SIZE);
    private static final ExecutorService synthExecutor = Executors.newFixedThreadPool(POOL_SIZE);
    private static boolean isSupertonic = false;
    private static int ttsModelSampleRate = 22050;

    public static void main(String[] args) throws Exception {

        String modelDir = System.getenv().getOrDefault("MODEL_DIR",
                          System.getProperty("model.dir", DEFAULT_MODEL_DIR));
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

        System.out.println("=================================================");
        System.out.println(" sherpa-onnx 한국어 TTS 서버 (v1.13.2 / Supertonic-3)");
        System.out.println("=================================================");
        System.out.println("[설정] 모델 디렉터리 : " + modelDir);
        System.out.println("[설정] 리슨 포트     : " + port);

        System.out.printf("[진행] TTS 엔진 초기화 중... (풀 크기: %d)%n", POOL_SIZE);
        for (int i = 0; i < POOL_SIZE; i++) {
            OfflineTts instance = initTts(modelDir);
            if (i == 0) ttsModelSampleRate = instance.getSampleRate();
            ttsPool.offer(instance);
        }
        System.out.println("[완료] TTS 엔진 초기화 성공.");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            synthExecutor.shutdown();
            ttsPool.forEach(OfflineTts::release);
            System.out.println("[정리] TTS 엔진 리소스 해제 완료.");
        }));

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/",       new UiHandler());
        server.createContext("/health", new HealthHandler());
        server.createContext("/tts",    new TtsHandler());
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();

        System.out.println("[시작] 서버 실행 중: http://0.0.0.0:" + port);
        System.out.println("=================================================");
    }

    // -------------------------------------------------------
    // GET / → 웹 UI
    // -------------------------------------------------------
    static class UiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestURI().getPath().equals("/")) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            byte[] bytes = HTML.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        }
    }

    // -------------------------------------------------------
    // GET /health
    // -------------------------------------------------------
    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            sendJson(exchange, 200, "{\"status\":\"ok\",\"engine\":\"supertonic-3\"}");
        }
    }

    // -------------------------------------------------------
    // POST /tts → WAV
    // -------------------------------------------------------
    static class TtsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // CORS 헤더 (브라우저에서 fetch 호출 허용)
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");

            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"POST 메서드만 허용됩니다\"}");
                return;
            }

            try {
                String reqBody = new String(
                        exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

                String text  = parseJsonString(reqBody, "text");
                float  speed = parseJsonFloat(reqBody,  "speed", 1.0f);
                int    sid   = parseJsonInt(reqBody,    "sid",   0);

                if (text == null || text.isBlank()) {
                    sendJson(exchange, 400, "{\"error\":\"text 필드가 필요합니다\"}");
                    return;
                }

                System.out.printf("[TTS] text=\"%.30s...\" speed=%.1f sid=%d%n",
                        text, speed, sid);
                long start = System.currentTimeMillis();

                byte[] wavBytes = generateWav(text, sid, speed);

                long elapsed = System.currentTimeMillis() - start;
                System.out.printf("[TTS] 완료 %d ms, %d bytes%n", elapsed, wavBytes.length);

                exchange.getResponseHeaders().set("Content-Type", "audio/wav");
                exchange.getResponseHeaders().set("Content-Disposition",
                        "inline; filename=\"tts.wav\"");
                exchange.getResponseHeaders().set("X-Elapsed-Ms", String.valueOf(elapsed));
                exchange.sendResponseHeaders(200, wavBytes.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(wavBytes); }

            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                System.err.println("[오류] " + cause.getMessage());
                sendJson(exchange, 500, "{\"error\":\"" + cause.getMessage() + "\"}");
            }
        }
    }

    // -------------------------------------------------------
    // 텍스트 분할 (구두점 기준)
    // -------------------------------------------------------
    private static List<String> splitText(String text) {
        String[] parts = text.split("(?<=[.!?。！？,，;；])");
        List<String> chunks = new ArrayList<>();
        for (String p : parts) {
            String t = p.strip();
            if (!t.isEmpty()) chunks.add(t);
        }
        return chunks.isEmpty() ? List.of(text.strip()) : chunks;
    }

    // -------------------------------------------------------
    // 단일 청크 합성 (풀에서 인스턴스 빌려 사용)
    // -------------------------------------------------------
    private static float[] synthesizeChunk(String chunk, int sid, float speed) {
        OfflineTts instance;
        try {
            instance = ttsPool.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("TTS 풀 대기 중단", e);
        }
        try {
            GeneratedAudio audio;
            if (isSupertonic) {
                GenerationConfig cfg = new GenerationConfig();
                cfg.setSid(sid);
                cfg.setSpeed(speed);
                Map<String, String> extra = new HashMap<>();
                extra.put("lang", "ko");
                cfg.setExtra(extra);
                audio = instance.generateWithConfigAndCallback(chunk, cfg, samples -> 1);
            } else {
                audio = instance.generate(chunk, sid, speed);
            }
            return audio.getSamples();
        } finally {
            ttsPool.offer(instance);
        }
    }

    // -------------------------------------------------------
    // 구두점 종류에 따른 무음 길이 (샘플 수)
    // -------------------------------------------------------
    private static int silenceSamples(String chunk) {
        char last = chunk.charAt(chunk.length() - 1);
        return switch (last) {
            case '.', '。'       -> ttsModelSampleRate * 250 / 1000; // 250ms
            case '?', '？',
                 '!', '！'       -> ttsModelSampleRate * 300 / 1000; // 300ms
            case ',', '，',
                 ';', '；'       -> ttsModelSampleRate * 120 / 1000; // 120ms
            default              -> ttsModelSampleRate * 150 / 1000; // 150ms
        };
    }

    // -------------------------------------------------------
    // TTS 생성 — 청크 병렬 합성 후 WAV 반환
    // -------------------------------------------------------
    private static byte[] generateWav(String text, int sid, float speed) throws Exception {
        List<String> chunks = splitText(text);
        System.out.printf("[TTS] 청크 %d개로 분할%n", chunks.size());

        List<CompletableFuture<float[]>> futures = chunks.stream()
                .map(chunk -> CompletableFuture.supplyAsync(
                        () -> synthesizeChunk(chunk, sid, speed), synthExecutor))
                .collect(Collectors.toList());

        // 순서 보장하며 샘플 수집
        List<float[]> results = new ArrayList<>();
        int totalLen = 0;
        for (int i = 0; i < futures.size(); i++) {
            float[] s = futures.get(i).join();
            results.add(s);
            totalLen += s.length;
            if (i < chunks.size() - 1) totalLen += silenceSamples(chunks.get(i));
        }

        // 이어붙이기 (청크 사이 구두점별 무음 삽입)
        float[] combined = new float[totalLen]; // 0.0f 로 초기화됨
        int pos = 0;
        for (int i = 0; i < results.size(); i++) {
            float[] s = results.get(i);
            System.arraycopy(s, 0, combined, pos, s.length);
            pos += s.length;
            if (i < chunks.size() - 1) pos += silenceSamples(chunks.get(i));
        }

        return samplesToWav(combined, ttsModelSampleRate);
    }

    // -------------------------------------------------------
    // float[] 샘플 → WAV 바이트
    // -------------------------------------------------------
    private static byte[] samplesToWav(float[] samples, int sampleRate) {
        int dataLen = samples.length * 2;
        ByteBuffer buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(new byte[]{'R','I','F','F'});
        buf.putInt(36 + dataLen);
        buf.put(new byte[]{'W','A','V','E','f','m','t',' '});
        buf.putInt(16);
        buf.putShort((short) 1);   // PCM
        buf.putShort((short) 1);   // mono
        buf.putInt(sampleRate);
        buf.putInt(sampleRate * 2);
        buf.putShort((short) 2);   // block align
        buf.putShort((short) 16);  // bits per sample
        buf.put(new byte[]{'d','a','t','a'});
        buf.putInt(dataLen);
        for (float s : samples) {
            buf.putShort((short) Math.max(-32768, Math.min(32767, (int)(s * 32767))));
        }
        return buf.array();
    }

    // -------------------------------------------------------
    // TTS 엔진 초기화 (tts.json 존재 시 Supertonic, 없으면 VITS)
    // -------------------------------------------------------
    private static OfflineTts initTts(String modelDir) throws IOException {
        Path dir = Paths.get(modelDir).toAbsolutePath();
        Path ttsJson = dir.resolve("tts.json");

        OfflineTtsModelConfig modelConfig;

        if (Files.exists(ttsJson)) {
            isSupertonic = true;
            System.out.println("[설정] 모델 타입 : Supertonic-3");
            String d = dir.toString() + "/";
            OfflineTtsSupertonicModelConfig supertonicConfig =
                    OfflineTtsSupertonicModelConfig.builder()
                            .setDurationPredictor(d + "duration_predictor.int8.onnx")
                            .setTextEncoder(d + "text_encoder.int8.onnx")
                            .setVectorEstimator(d + "vector_estimator.int8.onnx")
                            .setVocoder(d + "vocoder.int8.onnx")
                            .setTtsJson(d + "tts.json")
                            .setUnicodeIndexer(d + "unicode_indexer.bin")
                            .setVoiceStyle(d + "voice.bin")
                            .build();
            modelConfig = OfflineTtsModelConfig.builder()
                    .setSupertonic(supertonicConfig)
                    .setNumThreads(2)
                    .setDebug(false)
                    .setProvider("cpu")
                    .build();
        } else {
            isSupertonic = false;
            System.out.println("[설정] 모델 타입 : VITS");
            Path onnxFile   = resolveOnnxFile(dir);
            Path tokensFile = dir.resolve("tokens.txt");
            validateFile(onnxFile,   "ONNX 모델 파일");
            validateFile(tokensFile, "토큰 파일(tokens.txt)");
            Path espeakDir = dir.resolve("espeak-ng-data");
            String dataDir = Files.isDirectory(espeakDir) ? espeakDir.toString() : "";
            OfflineTtsVitsModelConfig vitsConfig = new OfflineTtsVitsModelConfig.Builder()
                    .setModel(onnxFile.toString())
                    .setTokens(tokensFile.toString())
                    .setLexicon("")
                    .setDataDir(dataDir)
                    .setNoiseScale(0.667f)
                    .setNoiseScaleW(0.8f)
                    .setLengthScale(1.0f)
                    .build();
            modelConfig = OfflineTtsModelConfig.builder()
                    .setVits(vitsConfig)
                    .setNumThreads(2)
                    .setDebug(false)
                    .setProvider("cpu")
                    .build();
        }

        OfflineTtsConfig ttsConfig = new OfflineTtsConfig.Builder()
                .setModel(modelConfig)
                .setRuleFsts("")
                .setMaxNumSentences(1)
                .build();

        return new OfflineTts(ttsConfig);
    }

    // -------------------------------------------------------
    // 유틸리티
    // -------------------------------------------------------
    private static Path resolveOnnxFile(Path dir) throws IOException {
        String name = System.getProperty("model.onnx");
        if (name != null && !name.isBlank()) return dir.resolve(name);
        Optional<Path> found = Files.list(dir)
                .filter(p -> p.toString().endsWith(".onnx"))
                .findFirst();
        if (found.isEmpty()) throw new RuntimeException(".onnx 파일 없음: " + dir);
        return found.get();
    }

    private static void validateFile(Path p, String desc) {
        if (!Files.exists(p)) {
            System.err.printf("[오류] %s 없음: %s%n", desc, p);
            System.exit(1);
        }
        System.out.printf("[확인] %-25s → %s%n", desc, p);
    }

    private static void sendJson(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static String parseJsonString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                           .matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static float parseJsonFloat(String json, String key, float def) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*([0-9.]+)").matcher(json);
        return m.find() ? Float.parseFloat(m.group(1)) : def;
    }

    private static int parseJsonInt(String json, String key, int def) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : def;
    }

    // -------------------------------------------------------
    // 웹 UI HTML (인라인)
    // -------------------------------------------------------
    private static final String HTML = """
<!DOCTYPE html>
<html lang="ko">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>한국어 TTS 데모</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: 'Noto Sans KR', 'Apple SD Gothic Neo', sans-serif;
      background: #f0f2f5;
      display: flex; justify-content: center; align-items: center;
      min-height: 100vh; padding: 20px;
    }
    .card {
      background: #fff; border-radius: 16px; padding: 36px 40px;
      width: 100%; max-width: 520px;
      box-shadow: 0 4px 24px rgba(0,0,0,0.10);
    }
    h1 { font-size: 1.4rem; color: #1a1a2e; margin-bottom: 24px; }
    label { display: block; font-size: 0.85rem; color: #555; margin-bottom: 6px; font-weight: 500; }
    textarea {
      width: 100%; height: 120px; border: 1.5px solid #dde1e7;
      border-radius: 10px; padding: 12px 14px; font-size: 1rem;
      resize: vertical; outline: none; transition: border-color .2s;
      font-family: inherit; color: #222;
    }
    textarea:focus { border-color: #4f6ef7; }
    .controls { display: flex; gap: 16px; margin: 16px 0; }
    .control-group { flex: 1; }
    input[type=range] { width: 100%; accent-color: #4f6ef7; }
    .range-row { display: flex; justify-content: space-between; align-items: center; }
    .range-val { font-size: 0.85rem; color: #4f6ef7; font-weight: 600; min-width: 32px; text-align: right; }
    .synth-btn {
      width: 100%; padding: 14px; background: #4f6ef7; color: #fff;
      border: none; border-radius: 10px; font-size: 1rem; font-weight: 600;
      cursor: pointer; transition: background .2s, transform .1s; margin-top: 8px;
    }
    .synth-btn:hover:not(:disabled) { background: #3a57e8; }
    .synth-btn:active:not(:disabled) { transform: scale(0.98); }
    .synth-btn:disabled { background: #a0aec0; cursor: not-allowed; }
    .status {
      margin-top: 16px; padding: 10px 14px; border-radius: 8px;
      font-size: 0.88rem; display: none;
    }
    .status.loading { background: #ebf0ff; color: #4f6ef7; display: block; }
    .status.success { background: #e6faf2; color: #1a7f5a; display: block; }
    .status.error   { background: #fff0f0; color: #c0392b; display: block; }
    audio { width: 100%; margin-top: 16px; border-radius: 8px; display: none; }
    audio.visible { display: block; }
  </style>
</head>
<body>
<div class="card">
  <h1>한국어 TTS 데모</h1>

  <label for="text">합성할 텍스트</label>
  <textarea id="text" placeholder="여기에 한국어 텍스트를 입력하세요.">안녕하세요. 한국어 TTS 데모입니다.</textarea>

  <div class="controls">
    <div class="control-group">
      <label>말하기 속도</label>
      <div class="range-row">
        <input type="range" id="speed" min="0.5" max="2.0" step="0.1" value="1.0">
        <span class="range-val" id="speedVal">1.0</span>
      </div>
    </div>
    <div class="control-group">
      <label>화자 ID</label>
      <div class="range-row">
        <input type="range" id="sid" min="0" max="10" step="1" value="0">
        <span class="range-val" id="sidVal">0</span>
      </div>
    </div>
  </div>

  <button class="synth-btn" id="btn" onclick="synthesize()">음성 합성</button>
  <div class="status" id="status"></div>
  <audio id="player" controls></audio>
</div>

<script>
  document.getElementById('speed').oninput = e =>
    document.getElementById('speedVal').textContent = (+e.target.value).toFixed(1);
  document.getElementById('sid').oninput = e =>
    document.getElementById('sidVal').textContent = e.target.value;

  async function synthesize() {
    const text  = document.getElementById('text').value.trim();
    const speed = parseFloat(document.getElementById('speed').value);
    const sid   = parseInt(document.getElementById('sid').value);
    const btn   = document.getElementById('btn');
    const player = document.getElementById('player');

    if (!text) { showStatus('error', '텍스트를 입력해 주세요.'); return; }

    btn.disabled = true;
    showStatus('loading', '음성 합성 중...');
    player.classList.remove('visible');

    try {
      const t0 = Date.now();
      const res = await fetch('/tts', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text, speed, sid }),
      });

      if (!res.ok) {
        const err = await res.json().catch(() => ({ error: res.statusText }));
        throw new Error(err.detail || err.error || res.statusText);
      }

      const blob = await res.blob();
      player.src = URL.createObjectURL(blob);
      player.classList.add('visible');
      player.play();
      showStatus('success', `완료 (${Date.now() - t0} ms · ${(blob.size/1024).toFixed(1)} KB)`);
    } catch (e) {
      showStatus('error', '오류: ' + e.message);
    } finally {
      btn.disabled = false;
    }
  }

  function showStatus(type, msg) {
    const el = document.getElementById('status');
    el.className = 'status ' + type;
    el.textContent = msg;
  }
</script>
</body>
</html>
""";
}
