package com.example.tts;

import com.k2fsa.sherpa.onnx.*;
import com.sun.net.httpserver.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.*;

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

    private static OfflineTts tts;
    // generate() 직렬화 (스레드 안전)
    private static final ReentrantLock ttsLock = new ReentrantLock();

    public static void main(String[] args) throws Exception {

        String modelDir = System.getenv().getOrDefault("MODEL_DIR",
                          System.getProperty("model.dir", DEFAULT_MODEL_DIR));
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

        System.out.println("=================================================");
        System.out.println(" sherpa-onnx 한국어 TTS 서버 (v1.12.0)");
        System.out.println("=================================================");
        System.out.println("[설정] 모델 디렉터리 : " + modelDir);
        System.out.println("[설정] 리슨 포트     : " + port);

        System.out.println("[진행] TTS 엔진 초기화 중...");
        tts = initTts(modelDir);
        System.out.println("[완료] TTS 엔진 초기화 성공.");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (tts != null) tts.release();
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
            sendJson(exchange, 200, "{\"status\":\"ok\",\"engine\":\"sherpa-onnx\"}");
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
                System.err.println("[오류] " + e.getMessage());
                sendJson(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    // -------------------------------------------------------
    // TTS 생성 (임시 파일 경유)
    // -------------------------------------------------------
    private static byte[] generateWav(String text, int sid, float speed) throws IOException {
        Path tmp = Files.createTempFile("tts-", ".wav");
        try {
            ttsLock.lock();
            try {
                GeneratedAudio audio = tts.generate(text, sid, speed);
                if (!audio.save(tmp.toString())) throw new IOException("WAV 저장 실패");
            } finally {
                ttsLock.unlock();
            }
            return Files.readAllBytes(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // -------------------------------------------------------
    // TTS 엔진 초기화
    // -------------------------------------------------------
    private static OfflineTts initTts(String modelDir) throws IOException {
        Path dir        = Paths.get(modelDir).toAbsolutePath();
        Path onnxFile   = resolveOnnxFile(dir);
        Path tokensFile = dir.resolve("tokens.txt");

        validateFile(onnxFile,   "ONNX 모델 파일");
        validateFile(tokensFile, "토큰 파일(tokens.txt)");

        Path espeakDir = dir.resolve("espeak-ng-data");
        String dataDir = Files.isDirectory(espeakDir) ? espeakDir.toString() : "";
        System.out.printf("[설정] espeak-ng-data : %s%n",
                dataDir.isEmpty() ? "(사용 안 함)" : dataDir);

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
      display: flex;
      justify-content: center;
      align-items: center;
      min-height: 100vh;
      padding: 20px;
    }
    .card {
      background: #fff;
      border-radius: 16px;
      padding: 36px 40px;
      width: 100%;
      max-width: 560px;
      box-shadow: 0 4px 24px rgba(0,0,0,0.10);
    }
    h1 { font-size: 1.4rem; color: #1a1a2e; margin-bottom: 24px; }
    label { display: block; font-size: 0.85rem; color: #555; margin-bottom: 6px; font-weight: 500; }

    /* 엔진 선택 토글 */
    .engine-toggle {
      display: flex;
      background: #f0f2f5;
      border-radius: 10px;
      padding: 4px;
      margin-bottom: 20px;
      gap: 4px;
    }
    .engine-btn {
      flex: 1;
      padding: 9px 0;
      border: none;
      border-radius: 7px;
      font-size: 0.88rem;
      font-weight: 600;
      cursor: pointer;
      background: transparent;
      color: #888;
      transition: all .2s;
      width: auto;
      margin-top: 0;
    }
    .engine-btn.active {
      background: #fff;
      color: #4f6ef7;
      box-shadow: 0 1px 6px rgba(0,0,0,0.12);
    }

    /* F5-TTS URL 입력 */
    #f5url-row { margin-bottom: 16px; }
    input[type=text] {
      width: 100%;
      border: 1.5px solid #dde1e7;
      border-radius: 8px;
      padding: 9px 12px;
      font-size: 0.9rem;
      outline: none;
      color: #333;
      transition: border-color .2s;
    }
    input[type=text]:focus { border-color: #4f6ef7; }

    textarea {
      width: 100%;
      height: 120px;
      border: 1.5px solid #dde1e7;
      border-radius: 10px;
      padding: 12px 14px;
      font-size: 1rem;
      resize: vertical;
      outline: none;
      transition: border-color .2s;
      font-family: inherit;
      color: #222;
    }
    textarea:focus { border-color: #4f6ef7; }
    .controls { display: flex; gap: 16px; margin: 16px 0; }
    .control-group { flex: 1; }
    input[type=range] { width: 100%; accent-color: #4f6ef7; }
    .range-row { display: flex; justify-content: space-between; align-items: center; }
    .range-val { font-size: 0.85rem; color: #4f6ef7; font-weight: 600; min-width: 32px; text-align: right; }
    .synth-btn {
      width: 100%;
      padding: 14px;
      background: #4f6ef7;
      color: #fff;
      border: none;
      border-radius: 10px;
      font-size: 1rem;
      font-weight: 600;
      cursor: pointer;
      transition: background .2s, transform .1s;
      margin-top: 8px;
    }
    .synth-btn:hover:not(:disabled) { background: #3a57e8; }
    .synth-btn:active:not(:disabled) { transform: scale(0.98); }
    .synth-btn:disabled { background: #a0aec0; cursor: not-allowed; }
    .status {
      margin-top: 16px; padding: 10px 14px;
      border-radius: 8px; font-size: 0.88rem; display: none;
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
  <h1>🔊 한국어 TTS 데모</h1>

  <label>엔진 선택</label>
  <div class="engine-toggle">
    <button class="engine-btn active" id="btn-sherpa" onclick="selectEngine('sherpa')">
      Sherpa-ONNX
    </button>
    <button class="engine-btn" id="btn-f5" onclick="selectEngine('f5')">
      F5-TTS
    </button>
  </div>

  <div id="f5url-row" style="display:none">
    <label for="f5url">F5-TTS 서버 주소</label>
    <input type="text" id="f5url" value="http://localhost:8000">
  </div>

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
    <div class="control-group" id="sid-group">
      <label>화자 ID (Sherpa 전용)</label>
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
  let engine = 'sherpa';

  document.getElementById('speed').oninput = e =>
    document.getElementById('speedVal').textContent = (+e.target.value).toFixed(1);
  document.getElementById('sid').oninput = e =>
    document.getElementById('sidVal').textContent = e.target.value;

  function selectEngine(e) {
    engine = e;
    document.getElementById('btn-sherpa').classList.toggle('active', e === 'sherpa');
    document.getElementById('btn-f5').classList.toggle('active', e === 'f5');
    document.getElementById('f5url-row').style.display = e === 'f5' ? 'block' : 'none';
    document.getElementById('sid-group').style.opacity = e === 'f5' ? '0.35' : '1';
    document.getElementById('sid-group').style.pointerEvents = e === 'f5' ? 'none' : 'auto';
  }

  async function synthesize() {
    const text  = document.getElementById('text').value.trim();
    const speed = parseFloat(document.getElementById('speed').value);
    const sid   = parseInt(document.getElementById('sid').value);
    const btn   = document.getElementById('btn');
    const player = document.getElementById('player');

    if (!text) { showStatus('error', '텍스트를 입력해 주세요.'); return; }

    btn.disabled = true;
    showStatus('loading', '⏳ 음성 합성 중...');
    player.classList.remove('visible');

    try {
      const t0 = Date.now();
      let url, body;

      if (engine === 'sherpa') {
        url  = '/tts';
        body = JSON.stringify({ text, speed, sid });
      } else {
        const base = document.getElementById('f5url').value.replace(/\\/$/, '');
        url  = base + '/tts';
        body = JSON.stringify({ text, speed });
      }

      const res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body,
      });

      if (!res.ok) {
        const err = await res.json().catch(() => ({ error: res.statusText }));
        throw new Error(err.detail || err.error || res.statusText);
      }

      const blob = await res.blob();
      const elapsed = Date.now() - t0;
      player.src = URL.createObjectURL(blob);
      player.classList.add('visible');
      player.play();

      const engineLabel = engine === 'sherpa' ? 'Sherpa-ONNX' : 'F5-TTS';
      showStatus('success', `✅ ${engineLabel} 합성 완료 (${elapsed} ms · ${(blob.size/1024).toFixed(1)} KB)`);
    } catch (e) {
      showStatus('error', '❌ 오류: ' + e.message);
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
