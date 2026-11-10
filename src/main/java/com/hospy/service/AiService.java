package com.hospy.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospy.dto.*;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Talks to Gemini and local fallbacks for clinical help. */
@Slf4j
@Service
public class AiService {

    public static final String MODO_DEMO_SIN_API_KEY =
            "MODO DEMO: Este resultado es una simulación educativa. " +
                "Configura GEMINI_API_KEY para usar Gemini en triage, SOAP, voz y farmacia.";

    @Value("${ai.gemini.api-key:}")
    private String geminiKey;

    @Value("${ai.gemini.model:gemini-3.6-flash}")
    private String geminiModel;

    @Value("${ai.gemini.fallback-models:gemini-3.8-flash}")
    private String geminiFallbackModels;

    /** Light model with a larger free quota: first on simple tasks, last fallback on the rest. */
    @Value("${ai.gemini.light-model:gemini-3.5-flash-lite}")
    private String geminiLightModel;

    private final Map<String, Long> modeloAgotadoHasta = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicLong haikuEntrada = new AtomicLong();
    private final AtomicLong haikuSalida = new AtomicLong();
    private final AtomicLong haikuLlamadas = new AtomicLong();

    @Autowired
    private SpecialtyService specialtyService;

    @Value("${ai.gemini.url:https://generativelanguage.googleapis.com/v1beta/models}")
    private String geminiBaseUrl;

    @Value("${ai.anthropic.api-key:}")
    private String anthropicKey;

    @Value("${ai.anthropic.model:claude-haiku-5-5}")
    private String anthropicModel;

    @Value("${ai.anthropic.url:https://api.anthropic.com/v1/messages}")
    private String anthropicUrl;

    @Value("${ai.openai.api-key:}")
    private String apiKey;

    @Value("${ai.openai.model:gpt-4o-mini}")
    private String model;

    @Value("${ai.openai.url:https://api.openai.com/v1/chat/completions}")
    private String apiUrl;

    @Value("${ai.openai.audio-url:https://api.openai.com/v1/audio/transcriptions}")
    private String audioApiUrl;

    @Value("${ai.openai.timeout-seconds:45}")
    private Integer timeout;

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final ClinicalDatasetService datasets;

    public AiService(ClinicalDatasetService datasets) {
        this.datasets = datasets;
        this.mapper = new ObjectMapper();
        this.mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_2)
                .build();
    }

    public boolean hasApiKey() {
        return hasGemini() || hasAnthropic() || hasOpenAi();
    }

    public boolean hasGemini() {
        return geminiKey != null && !geminiKey.isBlank();
    }

    @PostConstruct
    void logEstadoGemini() {
        log.info("Gemini: {} modelo={} | Claude: {}", hasGemini() ? "clave presente" : "SIN CLAVE", geminiModel,
                hasAnthropic() ? anthropicModel : "SIN CLAVE");
        Thread t = new Thread(() -> {
            try {
                warmupConsult();
            } catch (Exception ignored) {
                /* first consult still works without this handshake */
            }
        }, "hospy-haiku-warmup");
        t.setDaemon(true);
        t.start();
    }

    /** Warms Haiku for the chat and Gemini light for the doctor's SOAP summary. */
    public boolean warmupConsult() {
        boolean haiku = false;
        if (hasAnthropic()) {
            haiku = callAnthropic("Responde únicamente con la palabra LISTO.", "LISTO", 64).isPresent();
        }
        boolean gemini = false;
        if (hasGemini()) {
            String modelo = geminiLightModel == null || geminiLightModel.isBlank() ? geminiModel : geminiLightModel;
            gemini = callGeminiModel(modelo, "Responde únicamente con la palabra LISTO.", "LISTO",
                    null, null, false, 8, 8, false).isPresent();
        }
        return haiku || gemini;
    }

    public boolean hasAnthropic() {
        return !claveAnthropic().isBlank();
    }

    private String claveAnthropic() {
        return anthropicKey == null ? "" : anthropicKey.replaceAll("\\s+", "");
    }

    private List<String> anthropicModels() {
        List<String> modelos = new ArrayList<>();
        addModel(modelos, anthropicModel);
        addModel(modelos, "claude-haiku-5-5");
        addModel(modelos, "claude-haiku-4-5");
        if (modelos.size() > 2) return modelos.subList(0, 2);
        return modelos;
    }

    public boolean hasOpenAi() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String versionIa() {
        if (hasAnthropic()) {
            return "Hospy AI — Claude Haiku 5.5 · " + haikuLlamadas.get() + " respuestas · ~"
                    + gastoHaikuUsd() + " USD esta sesión";
        }
        if (hasGemini()) return "Hospy AI — Gemini (" + geminiModel + ")";
        if (hasOpenAi()) return "Hospy AI — OpenAI (" + model + ")";
        return "Hospy AI — modo demo local";
    }

    public Map<String, String> gastoHaiku() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("llamadas", String.valueOf(haikuLlamadas.get()));
        out.put("tokensEntrada", String.valueOf(haikuEntrada.get()));
        out.put("tokensSalida", String.valueOf(haikuSalida.get()));
        out.put("usdEstimado", gastoHaikuUsd());
        return out;
    }

    private String gastoHaikuUsd() {
        double usd = (haikuEntrada.get() / 1_000_000.0) * 0.10 + (haikuSalida.get() / 1_000_000.0) * 0.50;
        return String.format(java.util.Locale.US, "%.4f", usd);
    }

    /** Checks that Gemini answers. Does not invent metrics or persist data. */
    public Map<String, String> pingConnection() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("modelo", hasGemini() ? geminiModel : versionIa());
        if (!hasGemini()) {
            out.put("operativo", "false");
            out.put("detalle", "No hay clave de Gemini en el servidor.");
            return out;
        }
        Optional<String> respuesta = callGemini(
                "Responde únicamente con la palabra LISTO.",
                "Confirma que el modelo está disponible.",
                null, null, false, true);
        boolean ok = respuesta.isPresent() && !respuesta.get().isBlank();
        out.put("operativo", ok ? "true" : "false");
        out.put("detalle", ok
                ? "Gemini respondió con el modelo " + geminiModel + "."
                : "La clave está guardada, pero Gemini no respondió. Puede estar en su límite de uso.");
        return out;
    }

    public String transcriptionStatus() {
        if (hasGemini()) return "Operativo — Gemini Audio";
        if (hasOpenAi()) return "Operativo — Whisper";
        return "Demo (navegador Web Speech API)";
    }

    public AiTranscriptionResponse transcribe(byte[] audio, String contentType) {
        if (hasGemini()) {
            Optional<String> gemini = transcribeWithGemini(audio, contentType);
            if (gemini.isPresent() && !gemini.get().isBlank()) {
                return new AiTranscriptionResponse(gemini.get().trim(), false,
                        "Transcripción Gemini lista para revisión médica.");
            }
        }
        if (!hasOpenAi()) {
            String mensaje = hasGemini()
                    ? "Gemini no pudo transcribir el audio en este momento; puede estar en su límite de uso. Intenta en un minuto o escribe los datos."
                    : "Configura GEMINI_API_KEY para transcribir el audio. También puedes usar la voz del navegador.";
            return new AiTranscriptionResponse("", true, mensaje);
        }
        String boundary = "HospyBoundary" + UUID.randomUUID();
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            addPart(body, boundary, "model", "whisper-1");
            addPart(body, boundary, "language", "es");
            body.write(("--" + boundary + "\r\n" +
                    "Content-Disposition: form-data; name=\"file\"; filename=\"consulta.webm\"\r\n" +
                    "Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(audio);
            body.write("\r\n".getBytes(StandardCharsets.UTF_8));
            body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(audioApiUrl))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.warn("Transcripcion de audio fallo status={}", response.statusCode());
                return new AiTranscriptionResponse("", true, "Whisper no pudo transcribir el audio. Revisa la conexión y vuelve a intentar.");
            }
            String texto = mapper.readTree(response.body()).path("text").asText("");
            return new AiTranscriptionResponse(texto, false, "Transcripción Whisper lista para revisión médica.");
        } catch (Exception ex) {
            log.warn("Error en transcripcion de audio: {}", ex.getMessage());
            return new AiTranscriptionResponse("", true, "No se pudo completar la transcripción. Revisa la conexión y vuelve a intentar.");
        }
    }

    private void addPart(ByteArrayOutputStream body, String boundary, String nombre, String valor) throws Exception {
        body.write(("--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"" + nombre + "\"\r\n\r\n" +
                valor + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    /* ============ Llamada genérica al LLM (Gemini primero, OpenAI de respaldo) ============== */
    private Optional<String> callLlm(String systemPrompt, String userPrompt) {
        return callLlm(systemPrompt, userPrompt, false);
    }

    private Optional<String> callLlm(String systemPrompt, String userPrompt, boolean ligeroPrimero) {
        return callLlm(systemPrompt, userPrompt, ligeroPrimero, 0);
    }

    private Optional<String> callLlm(String systemPrompt, String userPrompt, boolean ligeroPrimero, int maxTokens) {
        if (hasAnthropic()) {
            Optional<String> claude = callAnthropic(systemPrompt, userPrompt, maxTokens);
            if (claude.isPresent()) return claude;
        }
        if (hasGemini()) {
            Optional<String> gemini = callGemini(systemPrompt, userPrompt, null, null, true, ligeroPrimero, maxTokens);
            if (gemini.isPresent()) return gemini;
        }
        if (hasOpenAi()) return callOpenAi(systemPrompt, userPrompt);
        return Optional.empty();
    }

    private Optional<String> callAnthropic(String systemPrompt, String userPrompt, int maxTokens) {
        return callAnthropic(systemPrompt, userPrompt, maxTokens, null, null, 5);
    }

    private Optional<String> callAnthropic(String systemPrompt, String userPrompt, int maxTokens,
                                           String mime, String base64, int timeoutSeconds) {
        if (!hasAnthropic()) return Optional.empty();
        int tokens = Math.max(maxTokens > 0 ? maxTokens : 512, 256);
        String clave = claveAnthropic();
        int espera = timeoutSeconds > 0 ? timeoutSeconds : 5;
        for (String modelo : anthropicModels()) {
            Optional<String> out = callAnthropicModel(modelo, clave, systemPrompt, userPrompt, tokens, mime, base64, espera);
            if (out.isPresent()) return out;
        }
        return Optional.empty();
    }

    private Optional<String> callAnthropicModel(String modelo, String clave, String systemPrompt, String userPrompt,
                                                int tokens, String mime, String base64, int timeoutSeconds) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", modelo);
            body.put("max_tokens", tokens);
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                body.put("system", systemPrompt);
            }
            Object content = userPrompt;
            if (base64 != null && !base64.isBlank()) {
                String tipo = (mime == null || mime.isBlank()) ? "image/jpeg" : mime;
                content = List.of(
                        Map.of("type", "image", "source", Map.of(
                                "type", "base64",
                                "media_type", tipo,
                                "data", base64)),
                        Map.of("type", "text", "text", userPrompt)
                );
            }
            body.put("messages", List.of(Map.of("role", "user", "content", content)));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(anthropicUrl))
                    .timeout(Duration.ofSeconds(timeoutSeconds > 0 ? timeoutSeconds : 5))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", clave)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                String snippet = resp.body() == null ? "" : resp.body().substring(0, Math.min(280, resp.body().length()));
                log.warn("Anthropic {} status={} body={}", modelo, resp.statusCode(), snippet);
                return Optional.empty();
            }
            JsonNode root = mapper.readTree(resp.body());
            contarUsoHaiku(root.path("usage"));
            JsonNode parts = root.path("content");
            StringBuilder texto = new StringBuilder();
            if (parts.isArray()) {
                for (JsonNode part : parts) {
                    String tipo = part.path("type").asText("");
                    if ("thinking".equals(tipo) || "redacted_thinking".equals(tipo)) continue;
                    String t = part.path("text").asText("");
                    if (!t.isBlank()) texto.append(t);
                }
            }
            return texto.isEmpty() ? Optional.empty() : Optional.of(texto.toString().trim());
        } catch (Exception ex) {
            log.warn("Excepción Anthropic {}: {}", modelo, ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> callOpenAi(String systemPrompt, String userPrompt) {
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "temperature", 0.1,
                    "response_format", Map.of("type", "json_object"),
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userPrompt)
                    )
            );
            String json = mapper.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                log.warn("Llamada a OpenAI fallo status={}", resp.statusCode());
                return Optional.empty();
            }
            JsonNode root = mapper.readTree(resp.body());
            String content = root.path("choices").get(0).path("message").path("content").asText();
            return Optional.ofNullable(cleanJson(content));
        } catch (Exception ex) {
            log.warn("Excepcion en llamada OpenAI: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> callGemini(String systemPrompt, String userPrompt, String mimeType, String base64, boolean json) {
        return callGemini(systemPrompt, userPrompt, mimeType, base64, json, false);
    }

    private Optional<String> callGemini(String systemPrompt, String userPrompt, String mimeType, String base64,
                                          boolean json, boolean ligeroPrimero) {
        return callGemini(systemPrompt, userPrompt, mimeType, base64, json, ligeroPrimero, 0);
    }

    private Optional<String> callGemini(String systemPrompt, String userPrompt, String mimeType, String base64,
                                          boolean json, boolean ligeroPrimero, int maxTokens) {
        if (!hasGemini()) return Optional.empty();
        long ahora = System.currentTimeMillis();
        for (String modelo : geminiModels(ligeroPrimero)) {
            if (modeloAgotadoHasta.getOrDefault(modelo, 0L) > ahora) continue;
            Optional<String> out = callGeminiModel(modelo, systemPrompt, userPrompt, mimeType, base64, json, maxTokens);
            if (out.isPresent()) return out;
        }
        return Optional.empty();
    }

    private List<String> geminiModels(boolean ligeroPrimero) {
        List<String> modelos = new ArrayList<>();
        if (ligeroPrimero) addModel(modelos, geminiLightModel);
        addModel(modelos, "gemini-3.6-flash");
        addModel(modelos, geminiModel);
        if (geminiFallbackModels != null) {
            for (String m : geminiFallbackModels.split(",")) addModel(modelos, m);
        }
        addModel(modelos, "gemini-3.8-flash");
        addModel(modelos, geminiLightModel);
        return modelos;
    }

    /** Consult stays on the light model plus one backup so the first question is not a six-model walk. */
    private List<String> geminiModelsConsult() {
        List<String> modelos = new ArrayList<>();
        addModel(modelos, geminiLightModel);
        addModel(modelos, "gemini-3.6-flash");
        return modelos;
    }

    private Optional<String> callLlmConsult(String systemPrompt, String userPrompt, int maxTokens) {
        if (hasAnthropic()) {
            Optional<String> claude = callAnthropic(systemPrompt, userPrompt, maxTokens);
            if (claude.isPresent()) return claude;
        }
        if (hasGemini()) {
            String modelo = geminiLightModel == null || geminiLightModel.isBlank() ? geminiModel : geminiLightModel;
            Optional<String> gemini = callGeminiModel(modelo, systemPrompt, userPrompt, null, null, false,
                    Math.max(maxTokens, 256), 4, false);
            if (gemini.isPresent()) return gemini;
        }
        return Optional.empty();
    }

    /** SOAP summary uses Gemini light first so "Preparando…" does not wait on Haiku or a six-model walk. */
    private Optional<String> callLlmSoap(String systemPrompt, String userPrompt) {
        if (hasGemini()) {
            String light = geminiLightModel == null || geminiLightModel.isBlank() ? geminiModel : geminiLightModel;
            Optional<String> rapido = callGeminiModel(light, systemPrompt, userPrompt, null, null, true,
                    900, 8, false);
            if (rapido.isPresent()) return rapido;
            Optional<String> flash = callGeminiModel("gemini-3.6-flash", systemPrompt, userPrompt, null, null, true,
                    900, 10, false);
            if (flash.isPresent()) return flash;
        }
        if (hasAnthropic()) return callAnthropic(systemPrompt, userPrompt, 900);
        return Optional.empty();
    }

    private void contarUsoHaiku(JsonNode usage) {
        if (usage == null || usage.isMissingNode()) return;
        haikuEntrada.addAndGet(Math.max(0, usage.path("input_tokens").asLong(0)));
        haikuSalida.addAndGet(Math.max(0, usage.path("output_tokens").asLong(0)));
        haikuLlamadas.incrementAndGet();
    }

    private static void addModel(List<String> modelos, String modelo) {
        if (modelo == null) return;
        String t = modelo.trim();
        if (t.isBlank() || retiredModel(t)) return;
        if (modelos.stream().noneMatch(t::equalsIgnoreCase)) modelos.add(t);
    }

    private static boolean retiredModel(String modelo) {
        String t = modelo.toLowerCase();
        return t.contains("gemini-2.0") || t.contains("gemini-1.5-flash-latest");
    }

    private Optional<String> callGeminiModel(String modelo, String systemPrompt, String userPrompt,
                                               String mimeType, String base64, boolean json) {
        return callGeminiModel(modelo, systemPrompt, userPrompt, mimeType, base64, json, 0);
    }

    private Optional<String> callGeminiModel(String modelo, String systemPrompt, String userPrompt,
                                               String mimeType, String base64, boolean json, int maxTokens) {
        return callGeminiModel(modelo, systemPrompt, userPrompt, mimeType, base64, json, maxTokens,
                Math.max(timeout == null ? 30 : timeout, 30), true);
    }

    private Optional<String> callGeminiModel(String modelo, String systemPrompt, String userPrompt,
                                               String mimeType, String base64, boolean json, int maxTokens,
                                               int timeoutSeconds, boolean retryOnLimit) {
        try {
            List<Map<String, Object>> parts = new ArrayList<>();
            parts.add(Map.of("text", userPrompt));
            if (base64 != null && !base64.isBlank() && mimeType != null) {
                parts.add(Map.of("inlineData", Map.of("mimeType", mimeType, "data", base64)));
            }
            Map<String, Object> body = new LinkedHashMap<>();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))));
            }
            body.put("contents", List.of(Map.of("role", "user", "parts", parts)));
            Map<String, Object> gen = new LinkedHashMap<>();
            if (!modelo.startsWith("gemini-3")) {
                gen.put("temperature", 0.2);
            }
            if (json) {
                gen.put("responseMimeType", "application/json");
            }
            if (maxTokens > 0) {
                gen.put("maxOutputTokens", maxTokens);
            }
            body.put("generationConfig", gen);

            String url = geminiBaseUrl + "/" + modelo + ":generateContent?key=" + geminiKey;
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(timeoutSeconds, 8)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long espera = retryOnLimit ? suggestedGeminiWait(resp.statusCode(), resp.body()) : 0;
            if (espera > 0) {
                log.info("Gemini {} status={}, reintento en {} ms", modelo, resp.statusCode(), espera);
                Thread.sleep(espera);
                resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            }
            if (resp.statusCode() != 200) {
                String snippet = resp.body() == null ? "" : resp.body().substring(0, Math.min(280, resp.body().length()));
                log.warn("Gemini {} status={} body={}", modelo, resp.statusCode(), snippet);
                if (resp.statusCode() == 429) {
                    boolean diaria = resp.body() != null && resp.body().contains("PerDay");
                    long hasta = System.currentTimeMillis() + (diaria ? 30 * 60_000L : 60_000L);
                    modeloAgotadoHasta.put(modelo, hasta);
                    if (diaria) {
                        for (String otro : geminiModelsConsult()) modeloAgotadoHasta.put(otro, hasta);
                    }
                }
                return Optional.empty();
            }
            Optional<String> texto = extractGeminiText(resp.body()).map(this::cleanJson);
            if (texto.isEmpty()) {
                String motivo = mapper.readTree(resp.body()).path("candidates").path(0).path("finishReason").asText("");
                String bloqueo = mapper.readTree(resp.body()).path("promptFeedback").path("blockReason").asText("");
                log.warn("Gemini {} respondió 200 sin texto útil finishReason={} blockReason={}", modelo, motivo, bloqueo);
            }
            return texto;
        } catch (Exception ex) {
            log.warn("Excepción Gemini {}: {}", modelo, ex.getMessage());
            String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
            if (ex instanceof java.net.http.HttpTimeoutException || msg.contains("timed out") || msg.contains("timeout")) {
                modeloAgotadoHasta.put(modelo, System.currentTimeMillis() + 90_000L);
            }
            return Optional.empty();
        }
    }

    /** 0 means do not wait. A 429 quota miss should skip to the next model or the fallback. */
    private long suggestedGeminiWait(int status, String cuerpo) {
        if (status == 503) return 1500;
        if (status != 429) return 0;
        Matcher m = Pattern.compile("\"retryDelay\"\\s*:\\s*\"(\\d+)(?:\\.\\d+)?s\"").matcher(cuerpo == null ? "" : cuerpo);
        long segundos = m.find() ? Long.parseLong(m.group(1)) : 99;
        return segundos <= 3 ? segundos * 1000 : 0;
    }

    private Optional<String> transcribeWithGemini(byte[] audio, String contentType) {
        String mime = (contentType == null || contentType.isBlank()) ? "audio/webm" : contentType.split(";")[0].trim();
        String prompt = "Transcribe fielmente este audio clínico en español. Devuelve solo el texto hablado, sin comentarios.";
        return callGemini(prompt, "Transcribe el audio.", mime, Base64.getEncoder().encodeToString(audio), false);
    }

    private Optional<String> extractGeminiText(String raw) {
        try {
            JsonNode root = mapper.readTree(raw);
            JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
            if (!parts.isArray() || parts.isEmpty()) return Optional.empty();
            StringBuilder texto = new StringBuilder();
            for (JsonNode part : parts) {
                String t = part.path("text").asText("");
                if (!t.isBlank()) texto.append(t);
            }
            return texto.isEmpty() ? Optional.empty() : Optional.of(texto.toString());
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private String cleanJson(String content) {
        if (content == null) return "";
        String t = content.trim();
        if (t.startsWith("```")) {
            t = t.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        return t.trim();
    }

    /* ============ 1. Triage / NLP sintomas -> ESI/MTS ============== */
    public AiTriageResponse triage(AiTriageRequest req) {
        AiTriageResponse local = datasets.triage(req);
        String fotoB64 = extraerBase64(req.getImagenBase64());
        boolean hayFoto = !fotoB64.isBlank();
        final String system = """
                Eres el médico de orientación de Hospy. Analiza ESTE caso con tu criterio y con la foto si existe.
                La pista de datasets es solo una referencia: si el relato o la imagen dicen otra cosa, corrígela.
                Habla solo de lo que el paciente contó o de lo que se ve en la foto. No inventes falta de aire, dolor de pecho ni otros síntomas que no mencionó.
                El resumen debe tener 4 a 6 oraciones en español: qué refirió, desde cuándo, antecedentes, qué se aprecia en la foto si hay, por qué ese nivel y que agende ya la especialidad mientras espera.
                Las recomendaciones deben decir qué vigilar de SUS molestias y qué hacer hasta la cita.
                especialidadRecomendada tiene que ser UNA de las especialidades disponibles, la que mejor encaje con ESTE relato.
                Si hay dolor de cabeza y además una lesión de piel, explica las dos cosas y elige la especialidad más urgente.
                NUNCA menciones datasets, modelos, ESI, HAM10000 ni precisión.
                Devuelve UNICAMENTE JSON:
                {"severidad":"Rojo|Naranja|Amarillo|Verde|Azul","escala":"orientación 5 niveles","nivelEsi":1,
                 "especialidadRecomendada":"...","prioridad":1,
                 "hallazgos":["hallazgos concretos de este caso"],"recomendaciones":"...","resumen":"..."}
                """;
        final String user = """
                Caso a analizar:
                - Síntomas: %s
                - Duración: %s
                - Antecedentes: %s
                - Edad: %s
                - Foto: %s
                - Especialidades disponibles (elige UNA): %s
                Pista interna de datasets, no la copies ni la cites: %s
                """.formatted(
                nvl(req.getSintomas()),
                nvl(req.getDuracion()),
                nvl(req.getAntecedentes()),
                req.getEdadPaciente() == null ? "no informado" : req.getEdadPaciente().toString(),
                hayFoto ? "sí, descríbela y úsala en el resumen" : "no",
                especialidadesDisponibles(),
                datasets.contextoTriageParaGemini(req));

        Optional<String> llm = consultarGemini(system, user, req.getImagenBase64());
        if (llm.isEmpty()) return orientarSinGemini(local, req);
        try {
            AiTriageResponse r = mapper.readValue(extraerJson(llm.get()), AiTriageResponse.class);
            r.setModoDemo(false);
            normalizarTriage(r, local);
            completarOrientacion(r, local, req);
            return limpiarTriage(r, req.getSintomas());
        } catch (Exception ex) {
            log.warn("No se pudo leer JSON de triage Gemini: {}", ex.getMessage());
            return orientarSinGemini(local, req);
        }
    }

    private AiTriageResponse triageMock(AiTriageRequest req) {
        String s = req.getSintomas().toLowerCase();
        String sev = "Verde";
        int esi = 4;
        int prio = 4;
        String esp = "Medicina General";
        List<String> hallazgos = new ArrayList<>(List.of("Triage simulado MTS-ESI v1"));
        if (s.contains("dolor en el pecho") || s.contains("infarto") || s.contains("desmayo")) {
            sev = "Rojo"; esi = 1; prio = 10; esp = "Cardiología";
            hallazgos.add("Bandera roja: sintomatología cardíaca aguda");
        } else if (s.contains("fiebre alta") || s.contains("dificultad para respirar") || s.contains("disnea")) {
            sev = "Naranja"; esi = 2; prio = 8; esp = "Neumología";
            hallazgos.add("Posible compromiso respiratorio");
        } else if (s.contains("dolor de cabeza") || s.contains("cefalea") || s.contains("mareo")) {
            sev = "Amarillo"; esi = 3; prio = 6; esp = "Neurología";
            hallazgos.add("Síntomas neurológicos");
        } else if (s.contains("piel") || s.contains("mancha") || s.contains("lesión") || s.contains("acné")) {
            sev = "Verde"; esi = 4; prio = 3; esp = "Dermatología";
            hallazgos.add("Sospecha de afección cutánea");
        } else if (s.contains("gripe") || s.contains("resfriado") || s.contains("tos")) {
            sev = "Azul"; esi = 5; prio = 2;
            hallazgos.add("Cuadro respiratorio viral probable");
        }
        return AiTriageResponse.builder()
                .severidad(sev).escala("ESI/MTS 5 niveles").nivelEsi(esi)
                .especialidadRecomendada(esp).prioridad(prio).hallazgos(hallazgos)
                .recomendaciones(MODO_DEMO_SIN_API_KEY +
                        " Acude a la especialidad y guarda este triaje para el profesional de salud.")
                .resumen("Triaje simulado basado en palabras clave para: " + req.getSintomas())
                .modoDemo(true)
                .build();
    }

    /* ============ 2. Dermatología (Gemini + modelo de imagen + datasets) ============== */
    public AiDermatologyResponse dermatology(AiDermatologyRequest req) {
        boolean hayImg = req.getImagenBase64() != null && !req.getImagenBase64().isBlank();
        boolean hayTxt = (req.getDescripcion() != null && !req.getDescripcion().isBlank())
                || (req.getSintomasAsociados() != null && !req.getSintomasAsociados().isBlank());
        if (!hayImg && !hayTxt) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Describe la lesión o sube una foto de evidencia.");
        }
        AiDermatologyResponse local = datasets.dermatology(req);
        final String system = """
                Eres dermatólogo de orientación de Hospy. Mira la foto y el relato de ESTA lesión.
                La pista del clasificador es solo referencia: descríbelo tú (zona, color, hinchazón, costra, pus, dolor) y corrígela si no coincide.
                Explica el riesgo de lo que ves, qué cuidados seguir mientras agenda la cita y cuándo ir antes.
                No hables de otros órganos si la foto y el texto son de piel.
                NUNCA menciones datasets, modelos, ExtraTrees, HAM10000 ni precisión.
                Responde SOLO JSON:
                {"nivelRiesgo":"BAJO|MEDIO|ALTO|CRITICO","scoreRiesgo":0.0,
                 "explicacion":"4 oraciones: qué se ve, dónde, por qué ese nivel y qué hacer hasta la cita",
                 "diagnosticosDiferenciales":["posibles cuadros de esta lesión"],
                 "caracteristicasObservadas":["lo que se ve en la foto o se describe"],
                 "recomendaciones":"cuidados concretos hasta ver al médico","advertencia":""}
                """;
        String user = userVisionPiel(req) + "\nPista interna, no la copies: " + datasets.contextoPielParaGemini(req);
        Optional<String> llm = consultarGemini(system, user, req.getImagenBase64());
        if (llm.isEmpty()) return limpiarPiel(local, req);
        try {
            AiDermatologyResponse r = mapper.readValue(extraerJson(llm.get()), AiDermatologyResponse.class);
            r.setModoDemo(false);
            return limpiarPiel(fusionarPiel(r, local), req);
        } catch (Exception ex) {
            log.warn("No se pudo leer JSON de dermatología Gemini: {}", ex.getMessage());
            return limpiarPiel(local, req);
        }
    }

    private AiDermatologyResponse dermatologyFallback(AiDermatologyRequest req) {
        String desc = nvl(req.getDescripcion()).toLowerCase();
        String riesgo = "BAJO";
        double score = 0.18;
        List<String> dd = List.of("Dermatitis alérgica", "Picadura de insecto");
        List<String> car = new ArrayList<>(List.of("Evaluación por texto (sin imagen)"));
        if (desc.contains("cambio de color") || desc.contains("asimetría") || desc.contains("sangra") || desc.contains("ulcera")) {
            riesgo = "ALTO"; score = 0.78;
            dd = List.of("Lesión pigmentada sospechosa (regla ABCD)", "Melanoma en DDx", "Carcinoma basocelular");
            car.add("Signos de alarma: asimetría, bordes irregulares, cambio rápido");
        } else if (desc.contains("roncha") || desc.contains("urticaria") || desc.contains("picazón")) {
            riesgo = "MEDIO"; score = 0.42;
            dd = List.of("Urticaria", "Dermatitis atópica", "Reacción medicamentosa");
            car.add("Patrón pruriginoso, posible mediadores inflamatorios");
        } else if (desc.contains("grano") || desc.contains("acné") || desc.contains("pústula")) {
            riesgo = "BAJO"; score = 0.22;
            dd = List.of("Acné vulgar grado I-II", "Foliculitis");
        }
        return AiDermatologyResponse.builder()
                .nivelRiesgo(riesgo).scoreRiesgo(score)
                .diagnosticosDiferenciales(dd).caracteristicasObservadas(car)
                .recomendaciones(MODO_DEMO_SIN_API_KEY +
                        " Aplica protección solar FPS 50+ y consulta dermatología presencial (biopsia si persiste).")
                .advertencia("ADVERTENCIA: Este pre-diagnóstico NO sustituye consulta con dermatólogo.")
                .modoDemo(true).build();
    }

    private static final List<String> ESPECIALIDADES_ADMIN = List.of(
            "Medicina General", "Cardiología", "Neurología", "Dermatología", "Traumatología",
            "Ortopedia", "Pediatría", "Ginecología", "Oftalmología", "Otorrinolaringología",
            "Gastroenterología", "Endocrinología", "Urología", "Psiquiatría", "Neumología",
            "Medicina Interna", "Reumatología", "Oncología", "Nefrología", "Infectología");

    /* ============ Admin: alta de médico o paciente por conversación ============== */
    public AiAdminOperationResponse adminOperation(AiAdminOperationRequest req) {
        String instruccion = req.getInstruccion() == null ? "" : req.getInstruccion().trim();
        AiAdminOperationResponse previo = readDraft(req.getBorrador());
        if (shouldRestartRegistration(instruccion, previo)) previo = null;
        AiAdminOperationResponse especialidadNueva = specialtyRegistration(instruccion, previo);
        if (especialidadNueva != null) return especialidadNueva;
        String catalogo = String.join(", ", catalogNames());
        final String system = """
                Eres Hospy y registras médicos y pacientes. Lees español natural, aunque venga incompleto o desordenado.
                El texto puede venir de voz: tiene muletillas (ah, eh, bueno, listo), repeticiones y palabras cortadas. Ignóralas.
                Lee TODA la conversación, no solo el último mensaje, y devuelve todos los datos que ya se dijeron en cualquier mensaje.
                Si un dato aparece dos veces, usa el más reciente.
                El administrador puede hablar en primera persona ("mi correo es…", "mi género es…"): son los datos de la persona que se registra.
                Correo dictado: "juan camilo arroba gmail punto com" o "juancamilo.gmail.com" es "juancamilo@gmail.com". Sin espacios.
                Fecha dictada: "25 de agosto del 2001" es "2001-08-25".
                Teléfono dictado: "321 08 25 25" es "321082525". Solo dígitos.
                Tipo de sangre: "A positivo" es "A+", "O negativo" es "O-".
                Contacto de emergencia: el nombre o el parentesco ("mi madre"). Si solo dicen un número para el contacto, ese número es telefonoEmergencia.
                Un campo que no se dijo va como "" (cadena vacía). Nunca escribas "No proporcionado", "N/A", "desconocido" ni "pendiente".
                Ejemplos de cómo escribe el administrador, sin copiar estos nombres: "crea este paciente", "paciente: Ana López",
                "el documento es 1087782595", "gmail ana@gmail.com", "la contraseña es Hospy2026", "12 años de experiencia",
                "licencia MED-10", "teléfono 3001234567", "lunes a viernes de 8 a 12 y de 2 a 5".
                No inventes correo, contraseña, documento, teléfono, licencia, años, horario ni datos clínicos. Si no lo dijo, déjalo vacío.
                Un paciente también necesita fechaNacimiento en formato yyyy-MM-dd, genero, tipoSangre, direccion, ciudad, alergias, contactoEmergencia, telefonoEmergencia y observaciones.
                Si dice ninguna, no tiene o no aplica, guarda "Ninguna".
                Conserva el borrador. El mensaje nuevo solo agrega o corrige.
                Si ya dijo que es paciente, accion=CREAR_PACIENTE. Si es médico, doctor o especialista, accion=CREAR_MEDICO.
                Si pide cancelar, accion=INFO.
                Especialidades válidas: """ + catalogo + """
                Si pide crear una especialización, eso no registra un médico ni un paciente.
                diasAtencion usa 1=lunes … 7=domingo, separados por coma. Horas en HH:mm.
                Si hay dos jornadas, llena horaInicio2 y horaFin2. passwordSugerida es solo la clave que él escribió.
                Devuelve SOLO JSON:
                {"accion":"CREAR_MEDICO|CREAR_PACIENTE|INFO","nombre":"","email":"","passwordSugerida":"",
                 "especialidad":"","anosExperiencia":null,"numeroLicencia":"","telefono":"","documento":"",
                 "consultorio":"","biografia":"","ciudad":"","direccion":"","genero":"","tipoSangre":"","alergias":"",
                 "fechaNacimiento":"","contactoEmergencia":"","telefonoEmergencia":"","observaciones":"",
                 "diasAtencion":"","horaInicio":"","horaFin":"","horaInicio2":"","horaFin2":"","horario":"",
                 "mensaje":"qué dato acabas de entender"}
                """;
        List<String> historial = (previo == null || req.getHistorial() == null ? List.<String>of() : req.getHistorial())
                .stream().filter(m -> m != null && !m.isBlank()).map(this::normalizeDictation).toList();
        instruccion = normalizeDictation(instruccion);
        if (isCancellation(instruccion)) {
            AiAdminOperationResponse cancelado = localAdminOperation(instruccion, null);
            cancelado.setModoDemo(false);
            return cancelado;
        }
        StringBuilder conversacion = new StringBuilder();
        int n = 1;
        for (String anterior : historial) conversacion.append(n++).append(". ").append(anterior.trim()).append('\n');
        conversacion.append(n).append(". ").append(instruccion);
        MascaraDatos mascara = new MascaraDatos();
        String borradorPrompt = mascara.ocultar(previo == null ? "{}" : writeDraft(cleanForPrompt(previo)));
        String user = "Datos ya entendidos (borrador):\n" + borradorPrompt
                + "\n\nConversación completa del administrador en este registro:\n" + mascara.ocultar(conversacion.toString());
        Optional<String> llm = callLlm(system + MascaraDatos.INSTRUCCION, user, true).map(mascara::revelar);
        AiAdminOperationResponse dichos = localAdminOperation(String.join("\n", historial) + "\n" + instruccion, null);
        AiAdminOperationResponse r = llm.flatMap(this::readAdminResponse).orElse(null);
        boolean entendioGemini = r != null;
        if (r == null) r = previo == null ? new AiAdminOperationResponse() : previo;
        else {
            clearBlanks(r);
            r = mergeRegistration(previo, r);
        }
        applyStatements(r, dichos, !entendioGemini);
        normalizeRegistration(r);
        r.setModoDemo(llm.isEmpty());
        return requireRegistrationData(r);
    }

    /**
     * Gemini corta la respuesta (finishReason SPII) cuando ve documentos, teléfonos y correos juntos.
     * Se le envían marcadores y los valores reales se restauran en la respuesta.
     */
    private static final class MascaraDatos {
        static final String INSTRUCCION = """

                Los correos y los números largos (documento, teléfono, licencia) llegan como marcadores [[CORREO_1]] o [[NUM_1]].
                Copia el marcador exacto en el campo que corresponda, por ejemplo "documento":"[[NUM_2]]". No los cambies ni los inventes.
                """;
        private static final Pattern CORREO = Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");
        private static final Pattern NUMERO = Pattern.compile("(?<![\\w\\[])(?!\\d{4}-\\d{2}-\\d{2})\\d(?:[ .-]?\\d){4,}(?![\\w\\]])");
        private final Map<String, String> valores = new LinkedHashMap<>();
        private final Map<String, String> marcadores = new LinkedHashMap<>();

        String ocultar(String texto) {
            if (texto == null) return "";
            String resultado = replaceMatch(texto, CORREO, "CORREO", v -> v.toLowerCase());
            return replaceMatch(resultado, NUMERO, "NUM", v -> v.replaceAll("[^0-9]", ""));
        }

        private String replaceMatch(String texto, Pattern patron, String tipo, java.util.function.UnaryOperator<String> normalizar) {
            Matcher m = patron.matcher(texto);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String valor = normalizar.apply(m.group());
                String marcador = marcadores.get(tipo + ":" + valor);
                if (marcador == null) {
                    marcador = tipo + "_" + (valores.size() + 1);
                    marcadores.put(tipo + ":" + valor, marcador);
                    valores.put(marcador, valor);
                }
                m.appendReplacement(sb, Matcher.quoteReplacement("[[" + marcador + "]]"));
            }
            m.appendTail(sb);
            return sb.toString();
        }

        String revelar(String texto) {
            if (texto == null) return null;
            String resultado = texto;
            List<String> claves = new ArrayList<>(valores.keySet());
            claves.sort((a, b) -> b.length() - a.length());
            for (String marcador : claves) {
                resultado = resultado.replaceAll("\\[*\\s*\\b" + marcador + "\\b\\s*\\]*", Matcher.quoteReplacement(valores.get(marcador)));
            }
            return resultado;
        }
    }

    private String normalizeDictation(String texto) {
        if (texto == null) return "";
        String t = texto.replaceAll("(?i)\\s+arroba\\s+", "@");
        for (int i = 0; i < 3; i++) {
            t = t.replaceAll("(?i)(@[\\w.-]+?)\\s+punto\\s+(\\w+)", "$1.$2");
            t = t.replaceAll("(?i)([\\w.]+)\\s+punto\\s+(?=[\\w.]*@)", "$1.");
        }
        t = t.replaceAll("(?i)\\b([\\w][\\w.+-]*?)\\.(gmail|hotmail|outlook|yahoo|icloud|live)\\.(com|es|co)\\b", "$1@$2.$3");
        Matcher partes = Pattern.compile("(?i)((?:correo(?:\\s+electr[oó]nico)?|e-?mail|gmail)\\s*[,:]?\\s*(?:es\\s+)?)((?:[\\p{L}\\d._-]+\\s+){0,3}[\\p{L}\\d._-]+)\\s*@\\s*").matcher(t);
        StringBuilder sb = new StringBuilder();
        while (partes.find()) {
            String usuario = sinAcentoAdmin(partes.group(2)).replaceAll("\\s+", "").toLowerCase();
            partes.appendReplacement(sb, Matcher.quoteReplacement(partes.group(1) + usuario + "@"));
        }
        partes.appendTail(sb);
        return sb.toString();
    }

    private boolean isCancellation(String texto) {
        String t = texto == null ? "" : sinAcentoAdmin(texto).toLowerCase();
        return t.contains("cancela") || t.contains("olvidalo") || t.contains("empezar de nuevo");
    }

    private AiAdminOperationResponse cleanForPrompt(AiAdminOperationResponse previo) {
        AiAdminOperationResponse copia = readDraft(writeDraft(previo));
        if (copia == null) return previo;
        copia.setMensaje(null);
        copia.setModoDemo(null);
        return copia;
    }

    private static final Pattern VALOR_VACIO = Pattern.compile(
            "(?i)^\\s*(no\\s+(proporcionad[oa]|informad[oa]|especificad[oa]|indicad[oa]|dad[oa]|mencionad[oa])|n/?a|null|ninguno?\\s+dato|desconocid[oa]|pendiente|sin\\s+dato|vac[ií]o|-+)\\s*\\.?\\s*$");

    private String blankIfFilled(String valor) {
        if (valor == null) return null;
        return VALOR_VACIO.matcher(valor).matches() ? null : valor.trim();
    }

    private void clearBlanks(AiAdminOperationResponse r) {
        r.setNombre(blankIfFilled(r.getNombre()));
        r.setEmail(blankIfFilled(r.getEmail()));
        r.setPasswordSugerida(blankIfFilled(r.getPasswordSugerida()));
        r.setEspecialidad(blankIfFilled(r.getEspecialidad()));
        r.setNumeroLicencia(blankIfFilled(r.getNumeroLicencia()));
        r.setTelefono(blankIfFilled(r.getTelefono()));
        r.setDocumento(blankIfFilled(r.getDocumento()));
        r.setConsultorio(blankIfFilled(r.getConsultorio()));
        r.setBiografia(blankIfFilled(r.getBiografia()));
        r.setCiudad(blankIfFilled(r.getCiudad()));
        r.setDireccion(blankIfFilled(r.getDireccion()));
        r.setGenero(blankIfFilled(r.getGenero()));
        r.setTipoSangre(blankIfFilled(r.getTipoSangre()));
        r.setAlergias(blankIfFilled(r.getAlergias()));
        r.setFechaNacimiento(blankIfFilled(r.getFechaNacimiento()));
        r.setContactoEmergencia(blankIfFilled(r.getContactoEmergencia()));
        r.setTelefonoEmergencia(blankIfFilled(r.getTelefonoEmergencia()));
        r.setObservaciones(blankIfFilled(r.getObservaciones()));
        r.setDiasAtencion(blankIfFilled(r.getDiasAtencion()));
        r.setHoraInicio(blankIfFilled(r.getHoraInicio()));
        r.setHoraFin(blankIfFilled(r.getHoraFin()));
        r.setHoraInicio2(blankIfFilled(r.getHoraInicio2()));
        r.setHoraFin2(blankIfFilled(r.getHoraFin2()));
        r.setHorario(blankIfFilled(r.getHorario()));
    }

    private static final List<String> DOMINIOS_CORREO = List.of(
            "gmail.com", "hotmail.com", "outlook.com", "yahoo.com", "yahoo.es", "live.com", "icloud.com", "hospy.com");

    private void normalizeRegistration(AiAdminOperationResponse r) {
        if (r.getEmail() != null) {
            String e = sinAcentoAdmin(r.getEmail()).toLowerCase()
                    .replaceAll("\\s*arroba\\s*", "@").replaceAll("\\s*punto\\s*", ".").replaceAll("\\s+", "");
            if (!e.contains("@")) {
                for (String dominio : DOMINIOS_CORREO) {
                    if (e.endsWith("." + dominio)) {
                        e = e.substring(0, e.length() - dominio.length() - 1) + "@" + dominio;
                        break;
                    }
                    if (e.endsWith(dominio) && e.length() > dominio.length()) {
                        e = e.substring(0, e.length() - dominio.length()) + "@" + dominio;
                        break;
                    }
                }
            }
            r.setEmail(e);
        }
        if (r.getTelefono() != null) r.setTelefono(r.getTelefono().replaceAll("[^0-9]", ""));
        if (r.getTelefonoEmergencia() != null) {
            String tel = r.getTelefonoEmergencia().replaceAll("[^0-9]", "");
            r.setTelefonoEmergencia(tel.isEmpty() ? null : tel);
        }
        if (!enBlanco(r.getContactoEmergencia()) && enBlanco(r.getTelefonoEmergencia())) {
            String soloDigitos = r.getContactoEmergencia().replaceAll("[^0-9]", "");
            if (soloDigitos.length() >= 7 && r.getContactoEmergencia().replaceAll("[0-9\\s-]", "").isBlank()) {
                r.setTelefonoEmergencia(soloDigitos);
                r.setContactoEmergencia(null);
            }
        }
        String fecha = dateFromText(r.getFechaNacimiento());
        if (fecha != null) r.setFechaNacimiento(fecha);
        if (r.getTipoSangre() != null) {
            Matcher ms = Pattern.compile("(?i)\\b(AB|A|B|O)\\s*([+-]|positivo|negativo)").matcher(r.getTipoSangre());
            if (ms.find()) {
                String signo = ms.group(2).toLowerCase().startsWith("n") || ms.group(2).equals("-") ? "-" : "+";
                r.setTipoSangre(ms.group(1).toUpperCase() + signo);
            }
        }
        if (r.getGenero() != null) {
            String g = sinAcentoAdmin(r.getGenero()).toLowerCase();
            if (g.startsWith("masc") || g.equals("hombre") || g.equals("m")) r.setGenero("Masculino");
            else if (g.startsWith("fem") || g.equals("mujer") || g.equals("f")) r.setGenero("Femenino");
        }
        if (r.getAlergias() != null) {
            String a = sinAcentoAdmin(r.getAlergias()).toLowerCase();
            if (a.matches(".*\\b(ninguna|ninguno|no tengo|no tiene|sin alergias|no aplica)\\b.*")) r.setAlergias("Ninguna");
        }
    }

    private static final List<String> MESES = List.of("enero", "febrero", "marzo", "abril", "mayo", "junio",
            "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre");

    private String dateFromText(String texto) {
        if (texto == null || texto.isBlank()) return null;
        String t = sinAcentoAdmin(texto).toLowerCase().replace("setiembre", "septiembre");
        if (t.matches("\\s*\\d{4}-\\d{2}-\\d{2}\\s*")) return t.trim();
        String fecha = null;
        Matcher iso = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})").matcher(t);
        while (iso.find()) fecha = iso.group();
        Matcher numerica = Pattern.compile("(\\d{1,2})[/-](\\d{1,2})[/-](\\d{4})").matcher(t);
        while (numerica.find()) {
            fecha = String.format("%s-%02d-%02d", numerica.group(3),
                    Integer.parseInt(numerica.group(2)), Integer.parseInt(numerica.group(1)));
        }
        Matcher hablada = Pattern.compile("(\\d{1,2})\\s*de\\s*(" + String.join("|", MESES) + ")\\s*(?:de|del)?\\s*(\\d{4})").matcher(t);
        while (hablada.find()) {
            int mes = MESES.indexOf(hablada.group(2)) + 1;
            fecha = String.format("%s-%02d-%02d", hablada.group(3), mes, Integer.parseInt(hablada.group(1)));
        }
        return fecha;
    }

    private void applyStatements(AiAdminOperationResponse destino, AiAdminOperationResponse dichos, boolean replaceMatch) {
        if (dichos == null) return;
        if (!replaceMatch) {
            mergeRegistration(dichos, destino);
            return;
        }
        if (!enBlanco(dichos.getNombre())) destino.setNombre(dichos.getNombre());
        if (!enBlanco(dichos.getEmail())) destino.setEmail(dichos.getEmail());
        if (!enBlanco(dichos.getEspecialidad())) destino.setEspecialidad(dichos.getEspecialidad());
        if (!enBlanco(dichos.getNumeroLicencia())) destino.setNumeroLicencia(dichos.getNumeroLicencia());
        if (!enBlanco(dichos.getTelefono())) destino.setTelefono(dichos.getTelefono());
        if (!enBlanco(dichos.getDocumento())) destino.setDocumento(dichos.getDocumento());
        if (!enBlanco(dichos.getPasswordSugerida())) destino.setPasswordSugerida(dichos.getPasswordSugerida());
        if (!enBlanco(dichos.getDiasAtencion())) destino.setDiasAtencion(dichos.getDiasAtencion());
        if (!enBlanco(dichos.getHoraInicio())) destino.setHoraInicio(dichos.getHoraInicio());
        if (!enBlanco(dichos.getHoraFin())) destino.setHoraFin(dichos.getHoraFin());
        if (!enBlanco(dichos.getHoraInicio2())) destino.setHoraInicio2(dichos.getHoraInicio2());
        if (!enBlanco(dichos.getHoraFin2())) destino.setHoraFin2(dichos.getHoraFin2());
        if (!enBlanco(dichos.getHorario())) destino.setHorario(dichos.getHorario());
        if (!enBlanco(dichos.getFechaNacimiento())) destino.setFechaNacimiento(dichos.getFechaNacimiento());
        if (!enBlanco(dichos.getTipoSangre())) destino.setTipoSangre(dichos.getTipoSangre());
        if (!enBlanco(dichos.getGenero())) destino.setGenero(dichos.getGenero());
        if (!enBlanco(dichos.getCiudad())) destino.setCiudad(dichos.getCiudad());
        if (!enBlanco(dichos.getDireccion())) destino.setDireccion(dichos.getDireccion());
        if (!enBlanco(dichos.getAlergias())) destino.setAlergias(dichos.getAlergias());
        if (!enBlanco(dichos.getContactoEmergencia())) destino.setContactoEmergencia(dichos.getContactoEmergencia());
        if (!enBlanco(dichos.getTelefonoEmergencia())) destino.setTelefonoEmergencia(dichos.getTelefonoEmergencia());
        if (dichos.getAnosExperiencia() != null) destino.setAnosExperiencia(dichos.getAnosExperiencia());
        if (!enBlanco(dichos.getAccion())) destino.setAccion(dichos.getAccion());
    }

    private Optional<AiAdminOperationResponse> readAdminResponse(String json) {
        try {
            return Optional.of(mapper.readValue(json, AiAdminOperationResponse.class));
        } catch (Exception ex) {
            log.warn("No se pudo leer el alta de Gemini: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private AiAdminOperationResponse readDraft(String borrador) {
        if (borrador == null || borrador.isBlank()) return null;
        try {
            return mapper.readValue(borrador, AiAdminOperationResponse.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private String writeDraft(AiAdminOperationResponse r) {
        try {
            return mapper.writeValueAsString(r);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private boolean isNewRegistration(String texto) {
        String t = texto == null ? "" : sinAcentoAdmin(texto).toLowerCase();
        boolean pideAlta = t.contains("crea") || t.contains("registrar") || t.contains("nuevo medico")
                || t.contains("nueva medica") || t.contains("nuevo paciente") || t.contains("nueva paciente");
        boolean diceQuien = t.contains("medic") || t.contains("doctor") || t.contains("especialista") || t.contains("paciente");
        return pideAlta && diceQuien;
    }

    private boolean shouldRestartRegistration(String texto, AiAdminOperationResponse previo) {
        String t = texto == null ? "" : sinAcentoAdmin(texto).toLowerCase();
        if (t.contains("cancela") || t.contains("olvidalo") || t.contains("empezar de nuevo") || t.contains("otra persona")) {
            return true;
        }
        if (previo == null || !isNewRegistration(texto)) return false;
        String nombre = extractRegistrationName(texto);
        return nombre != null && !enBlanco(previo.getNombre())
                && !sinAcentoAdmin(nombre).equalsIgnoreCase(sinAcentoAdmin(previo.getNombre()));
    }

    private AiAdminOperationResponse mergeRegistration(AiAdminOperationResponse previo, AiAdminOperationResponse nuevo) {
        if (nuevo == null) nuevo = new AiAdminOperationResponse();
        if (previo == null) return nuevo;
        if (enBlanco(nuevo.getNombre())) nuevo.setNombre(previo.getNombre());
        if (enBlanco(nuevo.getEmail())) nuevo.setEmail(previo.getEmail());
        if (enBlanco(nuevo.getEspecialidad())) nuevo.setEspecialidad(previo.getEspecialidad());
        if (enBlanco(nuevo.getNumeroLicencia())) nuevo.setNumeroLicencia(previo.getNumeroLicencia());
        if (enBlanco(nuevo.getTelefono())) nuevo.setTelefono(previo.getTelefono());
        if (enBlanco(nuevo.getConsultorio())) nuevo.setConsultorio(previo.getConsultorio());
        if (enBlanco(nuevo.getBiografia())) nuevo.setBiografia(previo.getBiografia());
        if (enBlanco(nuevo.getDocumento())) nuevo.setDocumento(previo.getDocumento());
        if (enBlanco(nuevo.getCiudad())) nuevo.setCiudad(previo.getCiudad());
        if (enBlanco(nuevo.getGenero())) nuevo.setGenero(previo.getGenero());
        if (enBlanco(nuevo.getAlergias())) nuevo.setAlergias(previo.getAlergias());
        if (enBlanco(nuevo.getFechaNacimiento())) nuevo.setFechaNacimiento(previo.getFechaNacimiento());
        if (enBlanco(nuevo.getDireccion())) nuevo.setDireccion(previo.getDireccion());
        if (enBlanco(nuevo.getTipoSangre())) nuevo.setTipoSangre(previo.getTipoSangre());
        if (enBlanco(nuevo.getContactoEmergencia())) nuevo.setContactoEmergencia(previo.getContactoEmergencia());
        if (enBlanco(nuevo.getTelefonoEmergencia())) nuevo.setTelefonoEmergencia(previo.getTelefonoEmergencia());
        if (enBlanco(nuevo.getObservaciones())) nuevo.setObservaciones(previo.getObservaciones());
        if (enBlanco(nuevo.getPasswordSugerida())) nuevo.setPasswordSugerida(previo.getPasswordSugerida());
        if (enBlanco(nuevo.getDiasAtencion())) nuevo.setDiasAtencion(previo.getDiasAtencion());
        if (enBlanco(nuevo.getHoraInicio())) nuevo.setHoraInicio(previo.getHoraInicio());
        if (enBlanco(nuevo.getHoraFin())) nuevo.setHoraFin(previo.getHoraFin());
        if (enBlanco(nuevo.getHoraInicio2())) nuevo.setHoraInicio2(previo.getHoraInicio2());
        if (enBlanco(nuevo.getHoraFin2())) nuevo.setHoraFin2(previo.getHoraFin2());
        if (enBlanco(nuevo.getHorario())) nuevo.setHorario(previo.getHorario());
        if (nuevo.getAnosExperiencia() == null) nuevo.setAnosExperiencia(previo.getAnosExperiencia());
        if (enBlanco(nuevo.getAccion()) || "PREGUNTAR".equalsIgnoreCase(nuevo.getAccion())
                || "INFO".equalsIgnoreCase(nuevo.getAccion())) {
            if (!enBlanco(previo.getAccion()) && !"INFO".equalsIgnoreCase(previo.getAccion())) {
                nuevo.setAccion(previo.getAccion());
            }
        }
        return nuevo;
    }

    private AiAdminOperationResponse localAdminOperation(String instruccion, AiAdminOperationResponse previo) {
        AiAdminOperationResponse r = previo == null ? new AiAdminOperationResponse() : previo;
        String original = instruccion == null ? "" : instruccion.trim();
        String t = sinAcentoAdmin(original).toLowerCase();
        if (t.contains("cancela") || t.contains("olvidalo") || t.contains("empezar de nuevo")) {
            return AiAdminOperationResponse.builder().accion("INFO").modoDemo(true)
                    .mensaje("Listo. Dime si el alta es de un médico o de un paciente.").build();
        }
        String rol = firstMentionedRole(t);
        if (rol != null) r.setAccion(rol);
        String nombre = extractRegistrationName(original);
        if (nombre != null) r.setNombre(nombre);
        String email = emailInText(original);
        if (email != null) r.setEmail(email);
        Integer anos = yearsInText(t);
        if (anos != null) r.setAnosExperiencia(anos);
        String licencia = extraerGrupo(original, "(?i)licencia\\s*(?:es|numero|n[uú]mero|:)?\\s*[:#]?\\s*([A-Za-z]{0,8}[\\s-]?\\d[\\d-]{1,20}|[A-Za-z0-9-]{3,30}\\d)");
        if (licencia != null) r.setNumeroLicencia(licencia.trim().replaceAll("\\s+", "-").toUpperCase());
        String documento = extraerGrupo(original, "(?i)(?:documento|c[eé]dula|cedula|dni)(?:\\s+de\\s+identidad)?\\s*[,:]?\\s*(?:es|n[uú]mero)?\\s*[:#]?\\s*((?:\\d[\\s.-]?){4,15}\\d|[A-Za-z0-9-]{5,20})");
        if (documento != null) {
            r.setDocumento(documento.matches("[\\d\\s.-]+") ? documento.replaceAll("[^0-9]", "") : documento);
        }
        String telefono = extraerGrupo(original, "(?i)(?:tel[eé]fono|celular|cel|whatsapp)\\s*(?:es|:)?\\s*([0-9][0-9 \\-]{6,18})");
        if (telefono != null) r.setTelefono(telefono.replaceAll("[^0-9]", ""));
        String clave = passwordInText(original);
        if (clave != null) r.setPasswordSugerida(clave);
        String esp = specialtyInText(original);
        if (esp != null) r.setEspecialidad(esp);
        Matcher ms = Pattern.compile("(?i)(?:sangre|rh|sangu[ií]neo)\\s*[,:]?\\s*(?:es\\s*)?[,:]?\\s*(AB|A|B|O)\\s*([+-]|positivo|negativo)").matcher(original);
        while (ms.find()) {
            String signo = ms.group(2).toLowerCase().startsWith("n") || ms.group(2).equals("-") ? "-" : "+";
            r.setTipoSangre(ms.group(1).toUpperCase() + signo);
        }
        Matcher mg = Pattern.compile("\\b(masculino|hombre|femenino|mujer)\\b").matcher(t);
        while (mg.find()) {
            r.setGenero(mg.group(1).startsWith("masc") || mg.group(1).equals("hombre") ? "Masculino" : "Femenino");
        }
        String fechaHablada = dateFromText(original);
        if (fechaHablada != null) r.setFechaNacimiento(fechaHablada);
        String fin = "(?=[.,;\\n]|\\s+y\\s+(?:el|la|mi|las|los)\\b|$)";
        String ciudad = extraerGrupo(original, "(?i)ciudad\\s*[,:]?\\s*(?:es\\s+)?(?:(?:la\\s+)?ciudad\\s+de\\s+|de\\s+)?([\\p{L}][\\p{L} '-]{1,40}?)" + fin);
        if (ciudad == null) ciudad = extraerGrupo(original, "(?i:vive\\s+en|reside\\s+en)\\s+(?:la\\s+ciudad\\s+de\\s+)?(\\p{Lu}\\p{L}+(?:\\s+\\p{Lu}\\p{L}+)?)(?=[.,;\\n]|\\s|$)");
        if (ciudad == null) ciudad = knownCity(original);
        if (ciudad != null) r.setCiudad(ciudad);
        String direccion = extraerGrupo(original, "(?i)direcci[oó]n\\s*(?:es|:)?\\s*(?:ah\\s+|eh\\s+)?([\\p{L}0-9#][^.;\\n]{2,80}?)"
                + "(?=,\\s*(?:mi|su|la|el|y|ciudad|tel[eé]fono|g[eé]nero|alergias?)\\b|\\s+y\\s+(?:mi|su|la|el)\\b|[.;\\n]|$)");
        if (direccion == null) {
            direccion = extraerGrupo(original, "(?i:vive\\s+en|reside\\s+en|queda\\s+en)\\s+(?:la\\s+|el\\s+)?((?i:calle|carrera|cra\\.?|kr|avenida|av\\.?|transversal|diagonal|manzana|mz|barrio)\\s*[^.;\\n]{2,70}?)(?=\\s+en\\s+\\p{Lu}|,\\s*(?i:su|mi|la|el|y)\\b|[.;\\n]|$)");
        }
        if (direccion != null) r.setDireccion(direccion);
        String alergia = extraerGrupo(original, "(?i)(?:al[eé]rgic[oa]s?\\s+a|alergias?\\s+(?:a|al|es|son|:))\\s+(?:la\\s+|el\\s+|los\\s+|las\\s+)?(?!ninguna|nada\\b|no\\b)([\\p{L}][\\p{L} ,y]{1,60}?)" + fin);
        if (alergia != null) r.setAlergias(alergia);
        else if (t.matches("(?s).*\\b(no tengo (ninguna|alergias)|ninguna alergia|sin alergias|no tiene alergias|alergias?[,:]?\\s*(no|ninguna|nada))\\b.*")) {
            r.setAlergias("Ninguna");
        }
        String contacto = extraerGrupo(original, "(?i)contacto de emergencia\\s*[,:]?\\s*(?:es\\s+)?(?:ah\\s+|eh\\s+)?([\\p{L}][\\p{L} '-]{1,50}?)" + fin);
        if (contacto != null) r.setContactoEmergencia(contacto);
        String telEmergencia = extraerGrupo(original, "(?i)(?:tel[eé]fono de emergencia|contacto de emergencia)[^.\\n\\d]{0,40}?([0-9][0-9 \\-]{6,18}[0-9])");
        if (telEmergencia != null) r.setTelefonoEmergencia(telEmergencia.replaceAll("[^0-9]", ""));
        Matcher llamar = Pattern.compile("(?i)(?:emergencia|urgencia)s?[^.\\n]{0,25}?(?:llamar|avisar|contactar|comunicarse\\s+con)\\s+a\\s+([\\p{L}][\\p{L} '-]{1,40}?)\\s*(?:,|al|al\\s+n[uú]mero|en\\s+el)\\s*([0-9][0-9 \\-]{6,18}[0-9])").matcher(original);
        while (llamar.find()) {
            r.setContactoEmergencia(llamar.group(1).trim());
            r.setTelefonoEmergencia(llamar.group(2).replaceAll("[^0-9]", ""));
        }
        fillSchedule(r, original);
        r.setModoDemo(true);
        return r;
    }

    /** Last spoken password; digit groups such as "0 27 1" are joined. */
    private String passwordInText(String original) {
        String conector = "\\s*(?:(?:ser[ií]a|ser[aá]|va\\s+a\\s+ser|queda|es|ponle|pon)\\s*)?[:,]?\\s*";
        Matcher m = Pattern.compile("(?i)(?:clave|contrase[nñ]a|password)" + conector
                + "((?:\\d[\\s-]?){3,19}\\d(?![\\w])|\\S{2,40})").matcher(original);
        String clave = null;
        while (m.find()) {
            String valor = m.group(1).trim().replaceAll("[,.;:]+$", "");
            if (valor.matches("[\\d\\s-]+")) valor = valor.replaceAll("[\\s-]", "");
            if (valor.equalsIgnoreCase("es") || valor.isBlank()) continue;
            clave = valor;
        }
        return clave;
    }

    private String firstMentionedRole(String t) {
        Matcher paciente = Pattern.compile("\\bpacientes?\\b").matcher(t);
        Matcher medico = Pattern.compile("\\b(medic[oa]s?|doctor[a]?|dr[a]?\\.|especialista)\\b").matcher(t);
        int p = paciente.find() ? paciente.start() : Integer.MAX_VALUE;
        int m = medico.find() ? medico.start() : Integer.MAX_VALUE;
        if (p == Integer.MAX_VALUE && m == Integer.MAX_VALUE) return null;
        return p < m ? "CREAR_PACIENTE" : "CREAR_MEDICO";
    }

    private static final List<String> CIUDADES = List.of(
            "Bogotá", "Medellín", "Cali", "Barranquilla", "Cartagena", "Bucaramanga", "Pereira", "Manizales", "Pasto",
            "Cúcuta", "Ibagué", "Santa Marta", "Villavicencio", "Armenia", "Neiva", "Popayán", "Montería", "Valledupar",
            "Sincelejo", "Tunja", "Florencia", "Riohacha", "Quibdó", "Yopal", "Mocoa", "Leticia", "Palmira",
            "Buenaventura", "Tuluá", "Soacha", "Bello", "Envigado", "Itagüí", "Ipiales", "Tumaco", "Jamundí", "Yumbo");

    private String knownCity(String texto) {
        String n = sinAcentoAdmin(texto).toLowerCase();
        String encontrada = null;
        int ultima = -1;
        for (String ciudad : CIUDADES) {
            Matcher m = Pattern.compile("\\b" + Pattern.quote(sinAcentoAdmin(ciudad).toLowerCase()) + "\\b").matcher(n);
            while (m.find()) {
                if (m.start() > ultima) {
                    ultima = m.start();
                    encontrada = ciudad;
                }
            }
        }
        return encontrada;
    }

    private String extractRegistrationName(String original) {
        String letras = "([\\p{L}][\\p{L} '-]{2,80})";
        String prefijo = "(?:(?:dr|dra|doctor|doctora)\\.?\\s+)?";
        String nombre = extraerGrupo(original, "(?i)(?:"
                + "(?:paciente|m[eé]dic[oa]|doctor[a]?|especialista)\\s*[:\\-]\\s*"
                + "|(?:crea(?:r)?|registra(?:r)?|inscribe|inscribir|dar\\s+de\\s+alta|da\\s+de\\s+alta)\\s+(?:(?:a\\s+la|al|a|la|el)\\s+)?(?:nuev[oa]\\s+)?(?:paciente|m[eé]dic[oa]|doctor[a]?|especialista)\\s+(?!nuev|por\\b|que\\b)"
                + "|(?:es\\s+)?(?:el|la)\\s+(?:doctor[a]?|dr[a]?\\.?)\\s+"
                + "|mi nombre es|su nombre es|el nombre es|nombre completo es|con el nombre(?: de)?|se llama|llamad[oa]|nombre\\s*:"
                + "|(?:^|[.,;\\n]\\s*)nombre(?:\\s+completo)?"
                + ")\\s*" + prefijo + letras);
        if (nombre == null) return null;
        nombre = nombre.split("(?i)\\s+(con|y|es|mi|su|el|la|correo|email|gmail|documento|tel[eé]fono|celular|licencia|especialidad|clave|contrase\\p{L}*|que|tiene|atiende)\\b")[0].trim();
        nombre = nombre.replaceAll("[,.:;]+$", "").trim();
        if (nombre.split("\\s+").length > 6 || nombre.length() < 3) return null;
        return nombre;
    }

    private static final Map<String, Integer> NUMEROS_HABLADOS = Map.ofEntries(
            Map.entry("un", 1), Map.entry("uno", 1), Map.entry("dos", 2), Map.entry("tres", 3), Map.entry("cuatro", 4),
            Map.entry("cinco", 5), Map.entry("seis", 6), Map.entry("siete", 7), Map.entry("ocho", 8), Map.entry("nueve", 9),
            Map.entry("diez", 10), Map.entry("once", 11), Map.entry("doce", 12), Map.entry("trece", 13), Map.entry("catorce", 14),
            Map.entry("quince", 15), Map.entry("dieciseis", 16), Map.entry("diecisiete", 17), Map.entry("dieciocho", 18),
            Map.entry("diecinueve", 19), Map.entry("veinte", 20), Map.entry("veintiuno", 21), Map.entry("veintidos", 22),
            Map.entry("veintitres", 23), Map.entry("veinticuatro", 24), Map.entry("veinticinco", 25), Map.entry("treinta", 30),
            Map.entry("cuarenta", 40));

    private Integer yearsInText(String t) {
        String digitos = extraerGrupo(t, "(\\d{1,2})\\s*anos(?:\\s+de\\s+experiencia)?");
        if (digitos == null) digitos = extraerGrupo(t, "experiencia\\s*(?:de|:|es)?\\s*(\\d{1,2})");
        if (digitos != null) return Integer.parseInt(digitos);
        String palabra = extraerGrupo(t, "\\b([a-z]+)\\s+anos(?:\\s+de\\s+experiencia)?");
        if (palabra != null && NUMEROS_HABLADOS.containsKey(palabra)) return NUMEROS_HABLADOS.get(palabra);
        return null;
    }

    private String emailInText(String original) {
        String t = sinAcentoAdmin(original).toLowerCase()
                .replaceAll("\\s+punto\\s+", ".")
                .replaceAll("\\s*arroba\\s*", "@");
        String correo = extraerGrupo(t, "([\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+)");
        if (correo != null) return correo.replaceAll("\\.+$", "");
        Matcher sinArroba = Pattern.compile("([\\w.+-]+?)\\.?(gmail|hotmail|outlook|yahoo|icloud|live)\\.(com|es|co)\\b").matcher(t);
        String encontrado = null;
        while (sinArroba.find()) {
            encontrado = sinArroba.group(1) + "@" + sinArroba.group(2) + "." + sinArroba.group(3);
        }
        return encontrado;
    }

    private static final Map<String, String> PROFESIONES = Map.ofEntries(
            Map.entry("pediatra", "Pediatría"), Map.entry("cardiologo", "Cardiología"), Map.entry("cardiologa", "Cardiología"),
            Map.entry("neurologo", "Neurología"), Map.entry("neurologa", "Neurología"),
            Map.entry("dermatologo", "Dermatología"), Map.entry("dermatologa", "Dermatología"),
            Map.entry("traumatologo", "Traumatología"), Map.entry("traumatologa", "Traumatología"),
            Map.entry("ortopedista", "Ortopedia"), Map.entry("ginecologo", "Ginecología"), Map.entry("ginecologa", "Ginecología"),
            Map.entry("oftalmologo", "Oftalmología"), Map.entry("oftalmologa", "Oftalmología"),
            Map.entry("otorrino", "Otorrinolaringología"), Map.entry("gastroenterologo", "Gastroenterología"),
            Map.entry("gastroenterologa", "Gastroenterología"), Map.entry("endocrinologo", "Endocrinología"),
            Map.entry("endocrinologa", "Endocrinología"), Map.entry("urologo", "Urología"), Map.entry("urologa", "Urología"),
            Map.entry("psiquiatra", "Psiquiatría"), Map.entry("neumologo", "Neumología"), Map.entry("neumologa", "Neumología"),
            Map.entry("internista", "Medicina Interna"), Map.entry("reumatologo", "Reumatología"),
            Map.entry("reumatologa", "Reumatología"), Map.entry("oncologo", "Oncología"), Map.entry("oncologa", "Oncología"),
            Map.entry("nefrologo", "Nefrología"), Map.entry("nefrologa", "Nefrología"), Map.entry("infectologo", "Infectología"),
            Map.entry("infectologa", "Infectología"), Map.entry("medico general", "Medicina General"),
            Map.entry("medica general", "Medicina General"));

    private String specialtyInText(String texto) {
        if (texto == null) return null;
        String n = sinAcentoAdmin(texto).toLowerCase();
        String mejor = null;
        int mejorLargo = 0;
        for (String especialidad : catalogNames()) {
            String e = sinAcentoAdmin(especialidad).toLowerCase();
            if (n.contains(e) && e.length() > mejorLargo) {
                mejor = especialidad;
                mejorLargo = e.length();
            }
        }
        if (mejor != null) return mejor;
        for (Map.Entry<String, String> profesion : PROFESIONES.entrySet()) {
            if (n.matches("(?s).*\\b" + profesion.getKey() + "s?\\b.*")) return profesion.getValue();
        }
        return null;
    }

    private static final List<String> DIAS = List.of("lunes", "martes", "miercoles", "jueves", "viernes", "sabado", "domingo");
    private static final List<String> NOMBRES_DIAS = List.of("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo");

    private void fillSchedule(AiAdminOperationResponse r, String original) {
        String t = sinAcentoAdmin(original).toLowerCase();
        if (!t.matches("(?s).*\\b(horario|horarios|atiende|atendera|trabaja|consulta|turno|jornada|lunes|martes|miercoles|jueves|viernes|sabado|domingo|entre semana|todos los dias)\\b.*")) {
            return;
        }
        String dias = null;
        String diaRegex = String.join("|", DIAS);
        Matcher rango = Pattern.compile("\\b(" + diaRegex + ")\\s+(?:a|al|hasta)\\s+(?:el\\s+)?(" + diaRegex + ")\\b").matcher(t);
        while (rango.find()) {
            int desde = DIAS.indexOf(rango.group(1)) + 1;
            int hasta = DIAS.indexOf(rango.group(2)) + 1;
            List<String> lista = new ArrayList<>();
            for (int d = desde; d <= hasta; d++) lista.add(String.valueOf(d));
            if (!lista.isEmpty()) dias = String.join(",", lista);
        }
        if (dias == null && t.contains("todos los dias")) dias = "1,2,3,4,5,6,7";
        if (dias == null && t.contains("entre semana")) dias = "1,2,3,4,5";
        if (dias == null) {
            Matcher sueltos = Pattern.compile("\\b(" + diaRegex + ")\\b").matcher(t);
            java.util.TreeSet<Integer> encontrados = new java.util.TreeSet<>();
            while (sueltos.find()) encontrados.add(DIAS.indexOf(sueltos.group(1)) + 1);
            if (!encontrados.isEmpty()) {
                dias = String.join(",", encontrados.stream().map(String::valueOf).toList());
            }
        }
        if (dias != null) r.setDiasAtencion(dias);

        String meridiano = "(de la manana|de la tarde|de la noche|del mediodia|am|pm|a\\.\\s?m\\.?|p\\.\\s?m\\.?)?";
        Matcher rangos = Pattern.compile("(?:\\b(?:de|desde|entre)\\s+(?:las\\s+)?)(\\d{1,2})(?::(\\d{2}))?\\s*" + meridiano
                + "\\s*(?:a|hasta|y\\s+las|-)\\s*(?:las\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*" + meridiano).matcher(t);
        List<String[]> jornadas = new ArrayList<>();
        while (rangos.find()) {
            int desde = Integer.parseInt(rangos.group(1));
            int hasta = Integer.parseInt(rangos.group(4));
            if (desde > 23 || hasta > 23) continue;
            desde = adjustMeridiem(desde, rangos.group(3));
            hasta = adjustMeridiem(hasta, rangos.group(6));
            boolean sinMeridiano = rangos.group(3) == null && rangos.group(6) == null;
            if (sinMeridiano && !jornadas.isEmpty() && desde <= 7) desde += 12;
            if (hasta <= desde && hasta + 12 <= 23) hasta += 12;
            if (hasta <= desde) continue;
            jornadas.add(new String[]{
                    String.format("%02d:%s", desde, rangos.group(2) == null ? "00" : rangos.group(2)),
                    String.format("%02d:%s", hasta, rangos.group(5) == null ? "00" : rangos.group(5))});
        }
        if (!jornadas.isEmpty()) {
            List<String[]> ultimas = jornadas.subList(Math.max(0, jornadas.size() - 2), jornadas.size());
            r.setHoraInicio(ultimas.get(0)[0]);
            r.setHoraFin(ultimas.get(0)[1]);
            if (ultimas.size() > 1) {
                r.setHoraInicio2(ultimas.get(1)[0]);
                r.setHoraFin2(ultimas.get(1)[1]);
            } else {
                r.setHoraInicio2(null);
                r.setHoraFin2(null);
            }
        }
        if (!enBlanco(r.getDiasAtencion()) && !enBlanco(r.getHoraInicio())) {
            r.setHorario(scheduleText(r));
        }
    }

    private int adjustMeridiem(int hora, String meridiano) {
        if (meridiano == null) return hora;
        String m = meridiano.replace(" ", "").replace(".", "");
        if ((m.contains("tarde") || m.contains("noche") || m.equals("pm")) && hora < 12) return hora + 12;
        if ((m.contains("manana") || m.equals("am")) && hora == 12) return 0;
        return hora;
    }

    private String scheduleText(AiAdminOperationResponse r) {
        String dias = switch (nvl(r.getDiasAtencion())) {
            case "1,2,3,4,5" -> "lunes a viernes";
            case "1,2,3,4,5,6" -> "lunes a sábado";
            case "1,2,3,4,5,6,7" -> "lunes a domingo";
            default -> {
                List<String> nombres = new ArrayList<>();
                for (String d : nvl(r.getDiasAtencion()).split(",")) {
                    String limpio = d.trim();
                    if (limpio.matches("[1-7]")) nombres.add(NOMBRES_DIAS.get(Integer.parseInt(limpio) - 1));
                }
                yield nombres.isEmpty() ? "días por confirmar" : String.join(", ", nombres);
            }
        };
        String horas = nvl(r.getHoraInicio()) + " a " + nvl(r.getHoraFin());
        if (!enBlanco(r.getHoraInicio2())) horas += " y " + r.getHoraInicio2() + " a " + r.getHoraFin2();
        return dias + ", " + horas;
    }

    private AiAdminOperationResponse requireRegistrationData(AiAdminOperationResponse r) {
        if (r == null) r = new AiAdminOperationResponse();
        String accion = r.getAccion() == null ? "" : r.getAccion().trim().toUpperCase();
        if ("INFO".equals(accion) && enBlanco(r.getNombre()) && enBlanco(r.getEmail()) && enBlanco(r.getDocumento())) {
            r.setAccion("INFO");
            r.setMensaje("Dime si el registro es de un médico o de un paciente, y el nombre.");
            return r;
        }
        if (!"CREAR_MEDICO".equals(accion) && !"CREAR_PACIENTE".equals(accion) && !"PREGUNTAR".equals(accion)) {
            r.setAccion("PREGUNTAR");
            r.setMensaje("¿El registro es de un médico o de un paciente?");
            return r;
        }
        if (r.getEmail() != null) r.setEmail(r.getEmail().trim().toLowerCase());
        if (r.getTelefono() != null) r.setTelefono(r.getTelefono().replaceAll("[^0-9]", ""));
        if (r.getPasswordSugerida() != null) r.setPasswordSugerida(r.getPasswordSugerida().trim());
        String esp = specialtyInText(r.getEspecialidad());
        if (esp == null) esp = normalizeSpecialty(r.getEspecialidad());
        if (esp != null) r.setEspecialidad(esp);
        boolean medico = "CREAR_MEDICO".equals(accion);
        if ("PREGUNTAR".equals(accion)) medico = !enBlanco(r.getEspecialidad()) || !enBlanco(r.getNumeroLicencia());
        if ("CREAR_PACIENTE".equals(accion)) medico = false;
        List<String> faltan = new ArrayList<>();
        if (enBlanco(r.getNombre())) faltan.add("el nombre completo");
        if (enBlanco(r.getEmail()) || !r.getEmail().contains("@")) faltan.add("el correo");
        String claveCorta = null;
        if (enBlanco(r.getPasswordSugerida())) {
            faltan.add("la contraseña (mínimo 6 caracteres)");
        } else if (r.getPasswordSugerida().length() < 6) {
            claveCorta = r.getPasswordSugerida();
            r.setPasswordSugerida(null);
        }
        if (medico) {
            r.setAccion("CREAR_MEDICO");
            if (esp == null) {
                String escrita = r.getEspecialidad() == null ? "" : r.getEspecialidad().trim();
                r.setEspecialidad(null);
                if (escrita.length() >= 3 && escrita.length() <= 40) {
                    faltan.add("una especialización registrada («" + escrita + "» no está; créala en este chat)");
                } else {
                    faltan.add("la especialidad");
                }
            }
            if (r.getAnosExperiencia() == null || r.getAnosExperiencia() < 0 || r.getAnosExperiencia() > 70) {
                faltan.add("los años de experiencia");
            }
            if (enBlanco(r.getNumeroLicencia())) faltan.add("el número de licencia");
            if (enBlanco(r.getTelefono()) || r.getTelefono().length() < 7) faltan.add("el teléfono");
            if (enBlanco(r.getDiasAtencion()) || enBlanco(r.getHoraInicio()) || enBlanco(r.getHoraFin())) {
                faltan.add("el horario de atención, con los días y las horas");
            }
        } else {
            r.setAccion("CREAR_PACIENTE");
            if (enBlanco(r.getDocumento())) faltan.add("el documento de identidad");
            if (enBlanco(r.getTelefono()) || r.getTelefono().length() < 7) faltan.add("el teléfono");
            if (enBlanco(r.getFechaNacimiento()) || !r.getFechaNacimiento().matches("\\d{4}-\\d{2}-\\d{2}")) {
                r.setFechaNacimiento(null);
                faltan.add("la fecha de nacimiento");
            }
            if (enBlanco(r.getGenero())) faltan.add("el género");
            if (enBlanco(r.getTipoSangre())) faltan.add("el tipo de sangre");
            if (enBlanco(r.getDireccion())) faltan.add("la dirección");
            if (enBlanco(r.getCiudad())) faltan.add("la ciudad");
            if (enBlanco(r.getAlergias())) faltan.add("las alergias (o «ninguna»)");
            if (enBlanco(r.getContactoEmergencia())) faltan.add("el contacto de emergencia");
            if (enBlanco(r.getTelefonoEmergencia())) faltan.add("el teléfono de emergencia");
        }
        if (claveCorta != null) {
            r.setAccion("PREGUNTAR");
            String resto = faltan.isEmpty() ? "" : " También me falta " + joinMissing(faltan) + ".";
            r.setMensaje("La contraseña «" + claveCorta + "» es muy corta: tiene " + claveCorta.length()
                    + " caracteres y debe tener mínimo 6. Dime otra, por ejemplo " + claveCorta + "2026." + resto);
            return r;
        }
        if (!faltan.isEmpty()) {
            r.setAccion("PREGUNTAR");
            String tipo = medico ? "médico" : "paciente";
            if (enBlanco(r.getNombre())) {
                r.setMensaje("Para registrar al " + tipo + " me falta " + joinMissing(faltan) + ". Puedes escribirlos como quieras.");
            } else {
                r.setMensaje(r.getNombre() + " ya quedó anotado como " + tipo + ". Para registrarlo me falta "
                        + joinMissing(faltan) + ". Puedes escribirlos como quieras.");
            }
            return r;
        }
        if (medico) {
            r.setHorario(scheduleText(r));
            r.setMensaje("Registro al médico " + r.getNombre() + ", " + r.getEspecialidad()
                    + ", " + r.getAnosExperiencia() + " años de experiencia, licencia " + r.getNumeroLicencia()
                    + ", horario " + r.getHorario() + ", teléfono " + r.getTelefono()
                    + ", correo " + r.getEmail() + ".");
        } else {
            r.setMensaje("Registro al paciente " + r.getNombre() + ", documento " + r.getDocumento()
                    + ", nacido el " + r.getFechaNacimiento() + ", " + r.getGenero() + ", sangre " + r.getTipoSangre()
                    + ", " + r.getDireccion() + ", " + r.getCiudad() + ", teléfono " + r.getTelefono()
                    + ", correo " + r.getEmail() + ".");
        }
        return r;
    }

    private String joinMissing(List<String> faltan) {
        if (faltan.size() == 1) return faltan.get(0);
        return String.join(", ", faltan.subList(0, faltan.size() - 1)) + " y " + faltan.get(faltan.size() - 1);
    }

    private String normalizeSpecialty(String valor) {
        if (valor == null || valor.isBlank()) return null;
        String n = sinAcentoAdmin(valor).toLowerCase();
        String mejor = null;
        for (String especialidad : catalogNames()) {
            String e = sinAcentoAdmin(especialidad).toLowerCase();
            if (n.equals(e)) return especialidad;
            if (n.contains(e)) return especialidad;
            if (e.contains(n) && n.length() >= 5) mejor = especialidad;
        }
        return mejor;
    }

    private List<String> catalogNames() {
        try {
            List<String> nombres = specialtyService.names();
            if (nombres != null && !nombres.isEmpty()) return nombres;
        } catch (Exception ex) {
            log.warn("No se pudo leer el catálogo de especializaciones: {}", ex.getMessage());
        }
        return ESPECIALIDADES_ADMIN;
    }

    private boolean looksLikePersonForm(String texto) {
        String t = sinAcentoAdmin(texto == null ? "" : texto).toLowerCase();
        int n = 0;
        if (t.contains("@") || t.contains("correo") || t.contains("gmail")) n++;
        if (t.contains("contrasena") || t.contains("clave") || t.contains("password")) n++;
        if (t.contains("licencia")) n++;
        if (t.contains("telefono") || t.contains("celular")) n++;
        if (t.contains("horario") || t.contains("lunes")) n++;
        if (t.contains("nombre completo") || t.contains("documento")) n++;
        if (t.contains("experiencia")) n++;
        return n >= 2;
    }

    private AiAdminOperationResponse specialtyRegistration(String instruccion, AiAdminOperationResponse previo) {
        String plano = sinAcentoAdmin(instruccion).toLowerCase();
        boolean pide = plano.contains("especializ") || plano.contains("especialidad");
        if (looksLikePersonForm(instruccion) || isNewRegistration(instruccion)) return null;
        boolean sigue = previo != null && "PREGUNTAR_ESPECIALIDAD".equals(previo.getAccion());
        boolean crear = plano.contains("crea") || plano.contains("agreg") || plano.contains("nueva")
                || plano.contains("quiero") || plano.contains("registr") || plano.contains("anade")
                || plano.contains("ponme") || plano.contains("mete");
        if (!sigue && !pide) return null;
        if (!sigue && pide && !crear) return null;
        String nombre = specialtyService.tidy(nameAfterSpecialtyWord(instruccion));
        if (nombre == null && sigue && !pide) nombre = specialtyService.tidy(instruccion);
        if (nombre == null && sigue) nombre = specialtyService.tidy(instruccion);
        if (nombre == null) {
            AiAdminOperationResponse r = copyDraft(previo);
            r.setAccion("PREGUNTAR_ESPECIALIDAD");
            r.setMensaje("¿Cómo se llama la especialización?");
            return r;
        }
        try {
            SpecialtyDto creada = specialtyService.create(nombre);
            String finalNombre = creada.getNombre();
            AiAdminOperationResponse r = copyDraft(previo);
            r.setEspecialidad(finalNombre);
            boolean siguePersona = previo != null && !enBlanco(previo.getNombre());
            if (siguePersona) {
                r.setAccion("PREGUNTAR");
                r.setMensaje((creada.isYaExistia()
                        ? finalNombre + " ya estaba en el listado."
                        : "Listo, creé la especialización " + finalNombre + ".")
                        + " Ya la puedes usar para el médico.");
            } else {
                r.setAccion("INFO");
                r.setNombre(null);
                r.setMensaje(creada.isYaExistia()
                        ? finalNombre + " ya estaba creada. Ya aparece al registrar o editar un médico."
                        : "Listo, creé la especialización " + finalNombre + ". Ya aparece al registrar o editar un médico.");
            }
            return r;
        } catch (RuntimeException ex) {
            AiAdminOperationResponse r = copyDraft(previo);
            r.setAccion("PREGUNTAR_ESPECIALIDAD");
            r.setMensaje(ex.getMessage() + " ¿Cómo se llama la especialización?");
            return r;
        }
    }

    private AiAdminOperationResponse copyDraft(AiAdminOperationResponse previo) {
        if (previo == null) return new AiAdminOperationResponse();
        AiAdminOperationResponse copia = readDraft(writeDraft(previo));
        return copia == null ? new AiAdminOperationResponse() : copia;
    }

    private String nameAfterSpecialtyWord(String texto) {
        if (texto == null) return null;
        Matcher m = Pattern.compile("(?i)(?:especializaci[oó]n|especialidad)\\s*[:\\-]?\\s*(?:se\\s+llama\\s+|llamada\\s+|es\\s+|de\\s+)?([\\p{L}][\\p{L} .'-]{2,80})").matcher(texto);
        String hallado = null;
        while (m.find()) hallado = m.group(1);
        if (hallado == null) return null;
        return hallado.replaceAll("(?i)\\s+(para|del|de)\\s+(los\\s+|las\\s+)?m[eé]dicos.*$", "").trim();
    }

    private String extraerGrupo(String texto, String regex) {
        if (texto == null) return null;
        Matcher m = Pattern.compile(regex).matcher(texto);
        String valor = null;
        while (m.find()) {
            String encontrado = m.group(1) == null ? "" : m.group(1).trim();
            if (!encontrado.isBlank()) valor = encontrado;
        }
        return valor;
    }

    private boolean enBlanco(String valor) {
        return valor == null || valor.isBlank();
    }

    private String sinAcentoAdmin(String valor) {
        return java.text.Normalizer.normalize(valor, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }

    /* ============ 3. Escriba SOAP médico ============== */
    public AiSoapResponse soap(AiSoapRequest req) {
        final String system = """
                Apoyas al médico en la consulta. No copies el aviso de triaje que ya vio el paciente.
                No uses semáforos, colores de severidad ni niveles ESI. No inventes signos vitales, dosis ni diagnósticos cerrados.
                Si un dato no está en el texto, escribe "No informado". El médico decide.
                Los medicamentos son opciones para que él elija, no una orden.
                Responde solo JSON. Las listas van como texto, un ítem por línea, nunca como arreglo:
                {"resumenParaMedico":"qué le pasa, en 3 o 4 frases para el médico",
                 "enfoqueEnConsulta":"qué examinar y qué decidir en esta cita",
                 "preguntasSugeridas":"pregunta 1\\npregunta 2\\npregunta 3",
                 "medicamentosSugeridos":"nombre · presentación habitual · para qué\\notro",
                 "cuidados":"ejercicio, reposo o señales para volver",
                 "subjetivo":"...","objetivo":"...","apreciacion":"...","plan":"...",
                 "diagnosticoPresuntivo":"...","procedimientosSugeridos":"..."}
                """;
        String user = "Nombre paciente: " + nvl(req.getNombrePaciente()) +
                " | Motivo inicial: " + nvl(req.getMotivoInicial()) +
                " | Transcripción consulta:\n" + req.getTranscripcionConsulta();

        Optional<String> llm = callLlmSoap(system, user);
        if (llm.isEmpty()) return soapMock(req);
        try {
            return leerSoap(llm.get());
        } catch (Exception ex) {
            return soapMock(req);
        }
    }

    public AiConsultaSimResponse consultaSimulada(AiConsultaSimRequest req) {
        final String system = """
                Eres el paciente en el consultorio. Hablas en primera persona, en español de Colombia, como una persona de verdad.
                Lee completo lo que acaba de decir el médico y responde ESO, en dos a cuatro frases.
                Sí o no cuando toque, y un porqué corto.
                Tu ficha (motivo, alergias, antecedentes) es tu verdad: si pregunta alergias, diles las de la ficha. No niegues lo que ya está escrito.
                Si pregunta algo que no está en la ficha (medicamento, mamá, té, casa), inventa un dato creíble y quédate con él.
                Si el médico indica tratamiento o se despide, confirma en una o dos frases. No recapitulés todo el plan.
                No preguntes a dónde volver. No inventes un síntoma nuevo al final. No cierres tú la consulta.
                Habla bonito, con ortografía correcta. No copies faltas. No des un diagnóstico. No JSON: solo lo que dices en voz alta.
                """;
        List<String> lineas = req.getDialogo() == null ? List.of() : req.getDialogo();
        String reciente = lineas.size() <= 8 ? String.join("\n", lineas)
                : String.join("\n", lineas.subList(lineas.size() - 8, lineas.size()));
        String pregunta = req.getPregunta() == null || req.getPregunta().isBlank()
                ? "saluda y cuenta lo que te trajo"
                : req.getPregunta().trim();
        String ficha = nvl(req.getContexto());
        if (ficha.length() > 900) ficha = ficha.substring(0, 900) + "...";
        String user = "Motivo: " + nvl(req.getMotivo()) +
                "\nFicha (alergias y relato; no lo niegues):\n" + (ficha.isBlank() ? "sin más dato" : ficha) +
                "\nConversación:\n" + (reciente.isBlank() ? "recién entra" : reciente) +
                "\nEl médico dijo: " + pregunta;
        Optional<String> llm = callLlmConsult(system, user, 400);
        if (llm.isEmpty()) return consultaMock(req);
        return hablarComoPaciente(llm.get(), req);
    }

    private AiConsultaSimResponse hablarComoPaciente(String bruto, AiConsultaSimRequest req) {
        String t = bruto == null ? "" : bruto.trim();
        if (t.contains("\"dicePaciente\"")) {
            try {
                AiConsultaSimResponse r = leerConsulta(t);
                if (r.getDicePaciente() != null && !r.getDicePaciente().isBlank()) return r;
            } catch (Exception ignored) {
                /* fall through to plain speech */
            }
        }
        t = t.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
        if (t.startsWith("\"") && t.endsWith("\"") && t.length() > 2) t = t.substring(1, t.length() - 1);
        if (t.isBlank()) return consultaMock(req);
        return AiConsultaSimResponse.builder()
                .dicePaciente(t)
                .pistaParaElMedico("")
                .medicamentosSugeridos("")
                .cuidados("")
                .modoDemo(false)
                .build();
    }

    private AiSoapResponse soapMock(AiSoapRequest req) {
        String t = req.getTranscripcionConsulta();
        String recorte = t == null ? "" : (t.length() > 280 ? t.substring(0, 280) + "..." : t);
        return AiSoapResponse.builder()
                .resumenParaMedico("El paciente consulta por: " + nvl(req.getMotivoInicial())
                        + ". En el relato cuenta: " + (recorte.isBlank() ? "sin más detalle." : recorte))
                .enfoqueEnConsulta("Confirma desde cuándo empezó, qué lo alivia y qué lo empeora. Examina la zona que refiere antes de decidir un medicamento.")
                .preguntasSugeridas("¿Desde cuándo le pasa y cómo empezó?\n¿Ha tomado algo y le sirvió?\n¿Hay fiebre, falta de aire o un dolor que se irradia?")
                .medicamentosSugeridos("Analgésico habitual · caja de tabletas · solo si no hay alergia y el examen lo permite\nMedida no farmacológica · reposo relativo y movimiento suave · mientras se aclara la causa")
                .cuidados("Movimiento suave de la zona si el dolor lo permite, calor local y volver si aparece fiebre, falta de aire o el dolor se vuelve intenso.")
                .subjetivo("Refiere: " + (recorte.isBlank() ? "no informado" : recorte))
                .objetivo("Signos vitales: no informados. Completar en el examen.")
                .apreciacion("Impresión pendiente del examen. No cierres el diagnóstico con este borrador.")
                .plan("1) Preguntar lo que falta. 2) Examinar. 3) Elegir medicamento solo si corresponde. 4) Dejar cuidados por escrito.")
                .diagnosticoPresuntivo("Pendiente de tu examen")
                .procedimientosSugeridos("No informado")
                .modoDemo(true).build();
    }

    /* ============ 4. Interacciones Fármaco-Alimento (GNN) ============== */
    public AiInteractionResponse interactions(AiInteractionRequest req) {
        final String system = """
                Eres un asistente de lenguaje para señalar posibles interacciones a verificar por un farmacéutico.
                No consultas DrugBank/Reactome ni eres una GNN; nunca presentes las respuestas como una verificación exhaustiva.
                Devuelve JSON estricto: {"nivelRiesgoGlobal":"NINGUNO|BAJO|MEDIO|ALTO|SEVERO",
                 "alertas":[{"elementos":"fármaco + alimento","tipo":"FARMACO_FARMACO|FARMACO_ALIMENTO|FARMACO_SUPLEMENTO",
                             "severidad":"...","mecanismo":"...","consecuencia":"...","accion":"..."}],
                 "recomendacionesDieteticas":["r1","r2"],"resumen":"..."}
                """;
        String user = "Medicamentos: " + String.join(", ", req.getMedicamentos()) +
                "\nDieta habitual: " + (req.getDietaHabitual() == null ? "no informada" : String.join(", ", req.getDietaHabitual())) +
                "\nSuplementos: " + (req.getSuplementos() == null ? "ninguno" : String.join(", ", req.getSuplementos()));

        Optional<String> llm = callLlm(system, user, true, 900);
        if (llm.isEmpty()) return datasets.interactions(req);
        try {
            AiInteractionResponse r = mapper.readValue(llm.get(), AiInteractionResponse.class);
            r.setModoDemo(false);
            return r;
        } catch (Exception ex) {
            return interaccionesMock(req);
        }
    }

    private AiInteractionResponse interaccionesMock(AiInteractionRequest req) {
        List<AiInteractionResponse.AlertaInteraccion> alertas = new ArrayList<>();
        List<String> recoms = new ArrayList<>(List.of(
                "Administrar medicamentos con un vaso de agua (240 mL)",
                "Separar ingesta de leche/lácteos 2h antes y 4h después de antibióticos",
                "Consulta farmacéutico para cada nueva molécula"));

        String riesgo = "BAJO";
        List<String> meds = req.getMedicamentos().stream().map(String::toLowerCase).toList();
        List<String> dieta = (req.getDietaHabitual() == null ? List.<String>of() :
                req.getDietaHabitual().stream().map(String::toLowerCase).toList());

        if (meds.stream().anyMatch(m -> m.contains("warfarina") || m.contains("sintrom"))
                && dieta.stream().anyMatch(d -> d.contains("verdura") || d.contains("espinaca") || d.contains("brócoli") || d.contains("vitamina k"))) {
            riesgo = "ALTO";
            alertas.add(AiInteractionResponse.AlertaInteraccion.builder()
                    .elementos("Warfarina + alimentos ricos en vitamina K").tipo("FARMACO_ALIMENTO")
                    .severidad("GRAVE").mecanismo("Antagonismo en ciclo reducción vitamina K epóxido")
                    .consecuencia("Pérdida de efecto anticoagulante; riesgo de TEV/ACV").accion("Constancia diaria en verduras; INR semanal")
                    .build());
            recoms.add("Mantener porción diaria constante de vegetales verdes");
        }
        if (meds.stream().anyMatch(m -> m.contains("estatina") || m.contains("atorvasta") || m.contains("simvasta"))
                && dieta.stream().anyMatch(d -> d.contains("toronja") || d.contains("pomelo") || d.contains("jugo de toronja"))) {
            riesgo = "MEDIO";
            alertas.add(AiInteractionResponse.AlertaInteraccion.builder()
                    .elementos("Estatinas + Toronja (pomelo)").tipo("FARMACO_ALIMENTO")
                    .severidad("MODERADA").mecanismo("Inhibición irreversible CYP3A4 y OATP1B1")
                    .consecuencia("Aumento de exposición sistémica x5; riesgo mialgia/rabdomiolisis")
                    .accion("Sustituir toronja por naranja dulce o mandarina").build());
        }
        if (meds.stream().anyMatch(m -> m.contains("tetraciclina") || m.contains("ciprofloxacino") || m.contains("fluoroquinolona"))
                && dieta.stream().anyMatch(d -> d.contains("leche") || d.contains("lácteo") || d.contains("queso") || d.contains("yogurt") || d.contains("calcio"))) {
            if (!riesgo.equals("ALTO")) riesgo = "MEDIO";
            alertas.add(AiInteractionResponse.AlertaInteraccion.builder()
                    .elementos("Fluoroquinolona/Tetraciclina + Lácteos/Ca").tipo("FARMACO_ALIMENTO")
                    .severidad("MODERADA").mecanismo("Quelación catiónica bivalente; insolubilidad")
                    .consecuencia("Biodisponibilidad reducida ~50%; falla en tratamiento").accion("Separar 2h antes / 4h después").build());
        }
        if (meds.stream().anyMatch(m -> m.contains("ibuprofeno") || m.contains("naproxeno") || m.contains("ainec") || m.contains("aspirina"))
                && dieta.stream().anyMatch(d -> d.contains("alcohol") || d.contains("cerveza") || d.contains("vino"))) {
            if (!riesgo.equals("ALTO")) riesgo = "MEDIO";
            alertas.add(AiInteractionResponse.AlertaInteraccion.builder()
                    .elementos("AINEs + Alcohol").tipo("FARMACO_ALIMENTO")
                    .severidad("MODERADA").mecanismo("Sinergia lesiva mucosa gástrica + alteración hemostasia")
                    .consecuencia("Ulcera péptica / hemorragia digestiva alta")
                    .accion("Suspender alcohol; protector gástrico si > 5 días de AINE").build());
        }
        if (alertas.isEmpty()) {
            alertas.add(AiInteractionResponse.AlertaInteraccion.builder()
                    .elementos("Evaluación combinada").tipo("GENERAL")
                    .severidad("INFORMATIVA").mecanismo("No se detectaron interacciones graves por palabras clave")
                    .consecuencia("Perfil bajo de interacciones en modo demo")
                    .accion(MODO_DEMO_SIN_API_KEY).build());
        }
        return AiInteractionResponse.builder()
                .nivelRiesgoGlobal(riesgo).alertas(alertas)
                .recomendacionesDieteticas(recoms)
                .resumen("GNN-demo evaluó " + meds.size() + " fármacos y " + dieta.size() + " alimentos. " +
                        (alertas.size()) + " alerta(s) detectada(s).")
                .modoDemo(true).build();
    }

    /* ============ 5. Triangulación precios medicamentos (Isolation Forest + Prophet) ============== */
    public AiPricesResponse prices(AiPricesRequest req) {
        final String system = """
                Estimas precios aproximados de medicamentos en Colombia, en pesos colombianos. No consultas inventario real.
                Si no te dan la presentación, usa la caja más habitual de ese medicamento.
                Los puntos de venta tienen que quedar en la ciudad pedida: centros comerciales, avenidas o el centro de ESA ciudad.
                En Pasto, por ejemplo, Unicentro, Valle de Atriz, el centro y la avenida Panamericana.
                El precio es un rango, no el valor exacto de hoy. Responde solo JSON:
                {"presentacionEstimada":"caja x 30 tabletas","precioEsperadoMin":18000,"precioEsperadoMax":42000,"precioPromedio":28000,
                 "evaluacionSobreprecio":"DENTRO_RANGO|SOBREPRECIO_BAJO|SOBREPRECIO_ALTO|DESABASTECIMIENTO_ARTIFICIAL",
                 "porcentajeDesviacion":0,
                 "comparativa":[{"nombre":"Cruz Verde","lugar":"Unicentro","presentacion":"caja x 30","precio":27000,
                   "disponibilidad":"Precio aproximado, sin confirmar stock","url":""}],
                 "alertas":["Es un rango, no el precio de caja de hoy"],
                 "recomendacion":"dónde suele salir más barato y qué presentación comparar"}
                """;
        String user = "Medicamento: " + req.getMedicamento() +
                " | Presentación: " + nvl(req.getPresentacion()) +
                " | Precio que le cobraron, si lo dijo: " + (req.getPrecioReportado() == null ? "no informado" : req.getPrecioReportado()) +
                " | Ciudad de Colombia: " + nvl(req.getCiudad()) +
                " | Devuelve 4 lugares distintos de esa ciudad, con el más barato primero.";

        Optional<String> llm = callLlm(system, user, true, 900);
        if (llm.isEmpty()) return preciosMock(req);
        try {
            AiPricesResponse r = mapper.readValue(llm.get(), AiPricesResponse.class);
            completarLugares(r, req);
            r.setModoDemo(false);
            return r;
        } catch (Exception ex) {
            return preciosMock(req);
        }
    }

    private AiPricesResponse preciosMock(AiPricesRequest req) {
        String med = req.getMedicamento().toLowerCase();
        double base = 30000.0;
        if (med.contains("ibuprofeno") || med.contains("acetaminofén") || med.contains("paracetamol")) base = 7500.0;
        else if (med.contains("amoxicilina")) base = 22000.0;
        else if (med.contains("omeprazol")) base = 14500.0;
        else if (med.contains("atorvasta") || med.contains("estatina")) base = 48000.0;
        else if (med.contains("metformina")) base = 18000.0;
        else if (med.contains("enalapril") || med.contains("losartán")) base = 26000.0;

        double min = base * 0.78;
        double max = base * 1.35;
        double pmed = base * 1.02;

        String presentacion = presentacionDe(req);
        List<String[]> puntos = puntosDeVenta(req.getCiudad());
        double[] factores = {0.86, 0.97, 1.08, 1.2};
        List<AiPricesResponse.ComparativaFarmacia> comp = new ArrayList<>();
        for (int i = 0; i < puntos.size() && i < factores.length; i++) {
            comp.add(AiPricesResponse.ComparativaFarmacia.builder()
                    .nombre(puntos.get(i)[0])
                    .lugar(puntos.get(i)[1])
                    .presentacion(presentacion)
                    .precio((double) Math.round(base * factores[i]))
                    .disponibilidad("Precio aproximado, sin confirmar stock")
                    .url("")
                    .build());
        }

        List<String> alertas = new ArrayList<>();
        String eval = "DENTRO_RANGO";
        double desv = 0.0;
        if (req.getPrecioReportado() != null) {
            desv = ((req.getPrecioReportado() - pmed) / pmed) * 100.0;
            if (desv > 45) { eval = "SOBREPRECIO_ALTO"; alertas.add("⚠️ Sobreprecio detectado (" + Math.round(desv) + "% > promedio)"); }
            else if (desv > 15) { eval = "SOBREPRECIO_BAJO"; alertas.add("ℹ️ Precio por encima del percentil 75"); }
            if (desv < -35) { eval = "DESABASTECIMIENTO_ARTIFICIAL"; alertas.add("🚨 Precio sospechosamente bajo: posible oferta sin stock (hoarding especulativo)"); }
        }
        String ciudad = nvl(req.getCiudad());
        alertas.add("Rango aproximado en " + ciudad + ". No es el precio de caja de hoy ni confirma que haya existencias.");

        return AiPricesResponse.builder()
                .presentacionEstimada(presentacion)
                .precioEsperadoMin((double) Math.round(min))
                .precioEsperadoMax((double) Math.round(max))
                .precioPromedio((double) Math.round(pmed))
                .evaluacionSobreprecio(eval)
                .porcentajeDesviacion(Math.round(desv * 100.0) / 100.0)
                .comparativa(comp).alertas(alertas)
                .recomendacion("En " + ciudad + " la opción más baja de esta lista es una referencia, no una oferta. Compara la misma presentación y el mismo principio activo.")
                .modoDemo(true).build();
    }

    private AiSoapResponse leerSoap(String json) throws Exception {
        JsonNode n = mapper.readTree(cleanJson(json));
        AiSoapResponse r = new AiSoapResponse();
        r.setResumenParaMedico(textoNodo(n, "resumenParaMedico"));
        r.setEnfoqueEnConsulta(textoNodo(n, "enfoqueEnConsulta"));
        r.setPreguntasSugeridas(textoNodo(n, "preguntasSugeridas"));
        r.setMedicamentosSugeridos(textoNodo(n, "medicamentosSugeridos"));
        r.setCuidados(textoNodo(n, "cuidados"));
        r.setSubjetivo(textoNodo(n, "subjetivo"));
        r.setObjetivo(textoNodo(n, "objetivo"));
        r.setApreciacion(textoNodo(n, "apreciacion"));
        r.setPlan(textoNodo(n, "plan"));
        r.setDiagnosticoPresuntivo(textoNodo(n, "diagnosticoPresuntivo"));
        r.setProcedimientosSugeridos(textoNodo(n, "procedimientosSugeridos"));
        r.setModoDemo(false);
        return r;
    }

    private AiConsultaSimResponse leerConsulta(String json) throws Exception {
        JsonNode n = mapper.readTree(cleanJson(json));
        return AiConsultaSimResponse.builder()
                .dicePaciente(textoNodo(n, "dicePaciente"))
                .pistaParaElMedico(textoNodo(n, "pistaParaElMedico"))
                .medicamentosSugeridos(textoNodo(n, "medicamentosSugeridos"))
                .cuidados(textoNodo(n, "cuidados"))
                .modoDemo(false)
                .build();
    }

    private AiConsultaSimResponse consultaMock(AiConsultaSimRequest req) {
        String motivo = nvl(req.getMotivo());
        String contexto = (req.getContexto() == null ? "" : req.getContexto()).toLowerCase();
        String pregunta = req.getPregunta() == null ? "" : sinAcentoAdmin(req.getPregunta()).toLowerCase();
        java.util.regex.Matcher dias = java.util.regex.Pattern.compile("(\\d+)\\s*d[ií]as").matcher(contexto);
        String cuanto = dias.find() ? dias.group(1) : "";
        String dice;
        if (pregunta.isBlank()) {
            dice = "Doctor, buenos días. Vengo porque " + (motivo.equals("no informado") ? "me siento mal y quiero que me revise." : motivo + ".")
                    + (cuanto.isBlank() ? "" : " Llevo así como " + cuanto + " días.");
        } else if (pregunta.contains("diarrea") || pregunta.contains("deposicion") || pregunta.contains("estuerzo")) {
            dice = "Diarrea no he tenido, doctor. Es más el dolor del estómago.";
        } else if (pregunta.contains("vomit")) {
            dice = "No, doctor, vómitos no he tenido. Solo el malestar en el estómago.";
        } else if (pregunta.contains("alerg")) {
            dice = "No, doctor. Alergias a medicamentos no tengo, que yo sepa.";
        } else if (pregunta.matches(".*\\b(tiempo|cuando|cuanto|desde cuando)\\b.*")
                || pregunta.contains("hace cuanto") || pregunta.contains("cuantos dia")) {
            dice = cuanto.isBlank()
                    ? "Empezó hace unos días, doctor. No sabría decirle la hora exacta."
                    : "Llevo más o menos " + cuanto + " días, doctor. Empezó de a poco y no se me ha quitado.";
        } else if (pregunta.contains("fiebre") || pregunta.contains("calentura")) {
            dice = "Fiebre no he tenido, doctor. Estoy molesto, pero sin escalofrío.";
        } else if (pregunta.contains("pastilla") || pregunta.contains("medicamento") || pregunta.contains("tomado")) {
            dice = "No he tomado nada formulado, doctor. A lo mucho algo de la casa, y el malestar sigue.";
        } else if (pregunta.contains("como te siente") || pregunta.contains("como se siente") || pregunta.contains("que siente")) {
            dice = "Me siento mal del " + (motivo.equals("no informado") ? "malestar" : motivo) + ", doctor. Al moverme se pone peor.";
        } else {
            dice = "Sí doctor, le escucho. Con esto que me pregunta, le cuento que sigo con " + motivo + " y por eso vine.";
        }
        return AiConsultaSimResponse.builder()
                .dicePaciente(dice)
                .pistaParaElMedico("")
                .medicamentosSugeridos("Analgésico habitual · tabletas · si no hay alergia y el examen lo permite")
                .cuidados("Calor local, caminar suave y volver si aparece fiebre o el dolor se va al pecho.")
                .modoDemo(true)
                .build();
    }

    private void completarLugares(AiPricesResponse r, AiPricesRequest req) {
        if (r.getPresentacionEstimada() == null || r.getPresentacionEstimada().isBlank()) {
            r.setPresentacionEstimada(presentacionDe(req));
        }
        if (r.getComparativa() == null) return;
        List<String[]> puntos = puntosDeVenta(req.getCiudad());
        for (int i = 0; i < r.getComparativa().size(); i++) {
            AiPricesResponse.ComparativaFarmacia f = r.getComparativa().get(i);
            String[] p = puntos.get(i % puntos.size());
            if (f.getNombre() == null || f.getNombre().isBlank()) f.setNombre(p[0]);
            if (f.getLugar() == null || f.getLugar().isBlank()) f.setLugar(p[1]);
            if (f.getPresentacion() == null || f.getPresentacion().isBlank()) f.setPresentacion(r.getPresentacionEstimada());
            if (f.getDisponibilidad() == null || f.getDisponibilidad().isBlank()) {
                f.setDisponibilidad("Precio aproximado, sin confirmar stock");
            }
        }
    }

    private String presentacionDe(AiPricesRequest req) {
        if (req.getPresentacion() != null && !req.getPresentacion().isBlank()) return req.getPresentacion().trim();
        String med = req.getMedicamento() == null ? "" : req.getMedicamento().toLowerCase();
        if (med.contains("jarabe") || med.contains("suspens")) return "frasco de jarabe";
        if (med.contains("crema") || med.contains("gel") || med.contains("ungüento") || med.contains("unguento")) return "tubo";
        if (med.contains("ampolla") || med.contains("inyect")) return "ampolla";
        return "caja de tabletas, presentación habitual";
    }

    private List<String[]> puntosDeVenta(String ciudad) {
        String c = sinAcentoAdmin(nvl(ciudad)).toLowerCase();
        if (c.contains("pasto")) return List.of(
                par("La Rebaja", "Valle de Atriz"),
                par("Cruz Verde", "Unicentro"),
                par("Droguería del centro", "Centro, calle 18"),
                par("Copservir", "Avenida Panamericana"));
        if (c.contains("bogota")) return List.of(
                par("Droguerías Colsubsidio", "Chapinero"),
                par("Cruz Verde", "Unicentro"),
                par("Farmatodo", "Salitre Plaza"),
                par("Locatel", "Centro Internacional"));
        if (c.contains("medellin")) return List.of(
                par("La Rebaja", "Laureles"),
                par("Cruz Verde", "El Tesoro"),
                par("Farmatodo", "Santa Fe"),
                par("Droguería de barrio", "Centro"));
        if (c.contains("cali")) return List.of(
                par("Cruz Verde", "Unicentro"),
                par("La Rebaja", "Chipichape"),
                par("Farmatodo", "Granada"),
                par("Droguería del centro", "Centro"));
        if (c.contains("barranquilla")) return List.of(
                par("Cruz Verde", "Portal del Prado"),
                par("La Rebaja", "Alto Prado"),
                par("Olímpica", "Centro"),
                par("Droguería de barrio", "Norte"));
        if (c.contains("bucaramanga")) return List.of(
                par("Cruz Verde", "Cacique"),
                par("La Rebaja", "Cabecera"),
                par("Droguería del centro", "Centro"),
                par("Copservir", "Cañaveral"));
        if (c.contains("cartagena")) return List.of(
                par("Cruz Verde", "Bocagrande"),
                par("La Rebaja", "Ejecutivos"),
                par("Droguería del centro", "Centro histórico"),
                par("Olímpica", "Bocagrande"));
        String sitio = c.contains("no informado") ? "la ciudad" : ciudad.trim();
        return List.of(
                par("La Rebaja", "Centro de " + sitio),
                par("Cruz Verde", "Centro comercial de " + sitio),
                par("Droguería de barrio", "Barrio céntrico de " + sitio),
                par("Olímpica", "Zona comercial de " + sitio));
    }

    private static String[] par(String nombre, String lugar) {
        return new String[] { nombre, lugar };
    }

    private String textoNodo(JsonNode n, String campo) {
        if (n == null) return "";
        JsonNode v = n.get(campo);
        if (v == null || v.isNull()) return "";
        if (v.isArray()) {
            StringBuilder sb = new StringBuilder();
            v.forEach(item -> {
                if (sb.length() > 0) sb.append('\n');
                sb.append(item.isValueNode() ? item.asText() : item.toString());
            });
            return sb.toString().trim();
        }
        return v.asText("").trim();
    }

    private static String nvl(String s) { return s == null || s.isBlank() ? "no informado" : s; }

    private static List<String> nvlList(List<String> in) {
        return in == null ? new ArrayList<>() : new ArrayList<>(in);
    }

    private static String userVisionPiel(AiDermatologyRequest req) {
        return "Observa la foto (si existe) y el relato del paciente. " +
                "Descripción: " + nvl(req.getDescripcion()) +
                " | Evolución: " + nvl(req.getTiempoEvolucion()) +
                " | Síntomas asociados: " + nvl(req.getSintomasAsociados()) +
                " | Foto: " + ((req.getImagenBase64() == null || req.getImagenBase64().isBlank())
                ? "no adjunta" : "adjunta, analízala pixel a pixel");
    }

    private static String extraerBase64(String dataUrl) {
        if (dataUrl == null) return "";
        int coma = dataUrl.indexOf(',');
        return coma >= 0 ? dataUrl.substring(coma + 1) : dataUrl;
    }

    private static String mimeDeDataUrl(String dataUrl) {
        if (dataUrl == null) return "image/jpeg";
        String t = dataUrl.toLowerCase();
        if (t.startsWith("data:image/png")) return "image/png";
        if (t.startsWith("data:image/webp")) return "image/webp";
        if (t.startsWith("data:image/gif")) return "image/gif";
        return "image/jpeg";
    }

    /** Claude first (text or photo). Gemini light only if Haiku does not answer. */
    private Optional<String> consultarGemini(String system, String user, String imagenDataUrl) {
        String b64 = extraerBase64(imagenDataUrl);
        String mime = mimeDeDataUrl(imagenDataUrl);
        boolean hayFoto = !b64.isBlank();
        if (hasAnthropic()) {
            Optional<String> claude = callAnthropic(system, user, 900, hayFoto ? mime : null, hayFoto ? b64 : null, 8);
            if (claude.isPresent()) return Optional.of(cleanJson(claude.get()));
        }
        if (hasGemini() && hayFoto) {
            String light = geminiLightModel == null || geminiLightModel.isBlank() ? geminiModel : geminiLightModel;
            Optional<String> vision = callGeminiModel(light, system, user, mime, b64, true, 900, 10, false);
            if (vision.isPresent()) return vision;
        }
        if (hasGemini()) {
            String light = geminiLightModel == null || geminiLightModel.isBlank() ? geminiModel : geminiLightModel;
            Optional<String> rapido = callGeminiModel(light, system, user, null, null, true, 900, 8, false);
            if (rapido.isPresent()) return rapido;
        }
        return Optional.empty();
    }

    private String especialidadesDisponibles() {
        try {
            List<String> nombres = specialtyService.names();
            if (nombres != null && !nombres.isEmpty()) return String.join(", ", nombres);
        } catch (Exception ignored) {
            /* catalog miss still lets Claude pick a usual specialty */
        }
        return String.join(", ", SpecialtyService.DEFAULTS);
    }

    private String extraerJson(String bruto) {
        String t = cleanJson(bruto);
        int i = t.indexOf('{');
        int f = t.lastIndexOf('}');
        return (i >= 0 && f > i) ? t.substring(i, f + 1) : t;
    }

    private static boolean esJergaTecnica(String s) {
        if (s == null) return false;
        String t = s.toLowerCase();
        return t.contains("dataset") || t.contains("ham10000") || t.contains("pad-ufes")
                || t.contains("extratrees") || t.contains("medmcqa") || t.contains("medqa")
                || t.contains("hugging") || t.contains("esi/mts") || t.contains("modelo local")
                || t.contains("precisión de prueba") || t.contains("bosque") || t.contains("extra trees")
                || t.contains("etiquetas tipo") || t.contains("clasificador") || t.contains("gemini no");
    }

    private static List<String> sinJerga(List<String> in) {
        List<String> out = new ArrayList<>();
        for (String s : nvlList(in)) {
            if (!s.isBlank() && !esJergaTecnica(s)) out.add(s);
        }
        return out;
    }

    private static AiTriageResponse limpiarTriage(AiTriageResponse r, String sintomas) {
        r.setHallazgos(sinJerga(r.getHallazgos()));
        r.setEscala("Orientación clínica");
        if (r.getResumen() != null && esJergaTecnica(r.getResumen())) {
            r.setResumen(null);
        }
        if (r.getRecomendaciones() != null && esJergaTecnica(r.getRecomendaciones())) {
            r.setRecomendaciones(null);
        }
        if (r.getResumen() == null || r.getResumen().isBlank()) {
            r.setResumen(orientacionDesdeRelato(sintomas, r));
        }
        if (r.getRecomendaciones() == null || r.getRecomendaciones().isBlank()) {
            r.setRecomendaciones(cuidadosHastaLaCita(r));
        }
        return r;
    }

    private AiTriageResponse orientarSinGemini(AiTriageResponse local, AiTriageRequest req) {
        normalizarTriage(local, local);
        local.setModoDemo(true);
        local.setResumen(orientacionDesdeRelato(req.getSintomas(), local));
        local.setRecomendaciones(cuidadosHastaLaCita(local));
        return limpiarTriage(local, req.getSintomas());
    }

    private static void completarOrientacion(AiTriageResponse r, AiTriageResponse local, AiTriageRequest req) {
        if (resumenPobre(r.getResumen(), local.getResumen())) {
            r.setResumen(orientacionDesdeRelato(req.getSintomas(), r));
        }
        if (cuidadosPobres(r.getRecomendaciones())) {
            r.setRecomendaciones(cuidadosHastaLaCita(r));
        }
    }

    private static boolean resumenPobre(String resumen, String local) {
        if (resumen == null || resumen.isBlank() || esJergaTecnica(resumen)) return true;
        String t = resumen.trim();
        if (local != null && t.equalsIgnoreCase(local.trim())) return true;
        String l = t.toLowerCase();
        return t.length() < 110 && l.startsWith("severidad ") && l.contains("hacia ");
    }

    private static boolean cuidadosPobres(String rec) {
        if (rec == null || rec.isBlank() || esJergaTecnica(rec)) return true;
        String t = rec.trim().toLowerCase();
        return t.equals("si empeora, hay fiebre o el dolor es intenso, consulta de forma presencial.");
    }

    private static String orientacionDesdeRelato(String sintomas, AiTriageResponse r) {
        String relato = sintomas == null || sintomas.isBlank() ? "lo que describes" : sintomas.trim();
        String sev = r.getSeveridad() == null ? "Amarillo" : r.getSeveridad();
        String esp = r.getEspecialidadRecomendada() == null ? "Medicina General" : r.getEspecialidadRecomendada();
        return "Cuentas: " + relato + ". La orientación es " + sev
                + " y lo adecuado es agendar " + esp
                + " ahora, mientras esperas la cita. Vigila si esa molestia aumenta, aparece fiebre o la zona que señalas se extiende; si pasa, acude antes de forma presencial.";
    }

    private static String cuidadosHastaLaCita(AiTriageResponse r) {
        String esp = r.getEspecialidadRecomendada() == null ? "la especialidad sugerida" : r.getEspecialidadRecomendada();
        return "Reserva la cita con " + esp
                + " sin dejar pasar el cuadro. Hasta ver al médico, descansa, anota cómo evoluciona lo que sientes y no te automediques con productos a los que seas alérgico. Si el dolor se vuelve mucho más fuerte, hay fiebre o la lesión crece, ve a urgencias antes de la cita.";
    }

    private static AiDermatologyResponse limpiarPiel(AiDermatologyResponse r, AiDermatologyRequest req) {
        if (r.getNivelRiesgo() != null) r.setNivelRiesgo(r.getNivelRiesgo().trim().toUpperCase());
        r.setCaracteristicasObservadas(sinJerga(r.getCaracteristicasObservadas()));
        r.setDiagnosticosDiferenciales(sinJerga(r.getDiagnosticosDiferenciales()));
        if (r.getAdvertencia() != null && esJergaTecnica(r.getAdvertencia())) r.setAdvertencia(null);
        if (r.getRecomendaciones() != null && esJergaTecnica(r.getRecomendaciones())) r.setRecomendaciones(null);
        if (r.getExplicacion() == null || r.getExplicacion().isBlank() || esJergaTecnica(r.getExplicacion())
                || r.getExplicacion().trim().length() < 80) {
            r.setExplicacion(explicacionPiel(r.getNivelRiesgo(), req));
        }
        if (r.getRecomendaciones() == null || r.getRecomendaciones().isBlank()) {
            r.setRecomendaciones("Agenda dermatología con esta foto y la descripción. Mientras tanto no aprietes la zona, mantenla limpia y consulta antes si crece, duele más o aparece fiebre.");
        }
        return r;
    }

    private static String explicacionPiel(String nivel, AiDermatologyRequest req) {
        String n = nivel == null ? "MEDIO" : nivel.toUpperCase();
        String porque = switch (n) {
            case "CRITICO" -> "Hay signos de alarma (dolor fuerte, extensión rápida o posible infección grave). Hay que valorarlo ya.";
            case "ALTO" -> "La zona se ve inflamada o alterada de forma importante. Puede ser infección o lesión que no debe esperarse.";
            case "MEDIO" -> "La lesión que describes conviene revisarla pronto, aunque no parece una emergencia inmediata.";
            default -> "El cambio de piel que describes parece leve. Vigílalo y consulta si empeora, hay fiebre o el dolor aumenta.";
        };
        String relato = (nvl(req.getDescripcion()).equals("no informado") ? "" : req.getDescripcion().trim());
        if (relato.isBlank() && req.getSintomasAsociados() != null) relato = req.getSintomasAsociados().trim();
        if (!relato.isBlank()) return porque + " Relato: " + relato + ".";
        return porque;
    }

    private static void normalizarTriage(AiTriageResponse r, AiTriageResponse local) {
        r.setHallazgos(sinJerga(r.getHallazgos()));
        if (r.getEspecialidadRecomendada() == null || r.getEspecialidadRecomendada().isBlank()) {
            r.setEspecialidadRecomendada(local.getEspecialidadRecomendada());
        }
        if (r.getSeveridad() == null || r.getSeveridad().isBlank()) r.setSeveridad(local.getSeveridad());
        else r.setSeveridad(capitalizarSeveridad(r.getSeveridad()));
        if (r.getNivelEsi() == null) r.setNivelEsi(local.getNivelEsi());
        if (r.getPrioridad() == null) r.setPrioridad(local.getPrioridad());
        if (r.getEscala() == null || r.getEscala().isBlank() || esJergaTecnica(r.getEscala())) {
            r.setEscala("Orientación clínica");
        }
        if (r.getResumen() == null || r.getResumen().isBlank() || esJergaTecnica(r.getResumen())) {
            r.setResumen(local.getResumen());
        }
    }

    private static String capitalizarSeveridad(String s) {
        String t = s.trim().toLowerCase();
        if (t.contains("rojo")) return "Rojo";
        if (t.contains("naranja")) return "Naranja";
        if (t.contains("amarillo")) return "Amarillo";
        if (t.contains("azul")) return "Azul";
        if (t.contains("verde")) return "Verde";
        return s;
    }

    private static AiDermatologyResponse fusionarPiel(AiDermatologyResponse gemini, AiDermatologyResponse local) {
        if (gemini.getNivelRiesgo() == null || gemini.getNivelRiesgo().isBlank()) {
            gemini.setNivelRiesgo(local.getNivelRiesgo());
        } else {
            gemini.setNivelRiesgo(gemini.getNivelRiesgo().trim().toUpperCase());
        }
        if (gemini.getScoreRiesgo() == null) gemini.setScoreRiesgo(local.getScoreRiesgo());
        if (gemini.getExplicacion() == null || gemini.getExplicacion().isBlank()) {
            gemini.setExplicacion(local.getExplicacion());
        }
        return gemini;
    }
}
