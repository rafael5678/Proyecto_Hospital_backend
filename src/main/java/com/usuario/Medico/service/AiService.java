package com.usuario.Medico.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.usuario.Medico.dto.*;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
@Service
public class AiService {

    public static final String MODO_DEMO_SIN_API_KEY =
            "MODO DEMO: Este resultado es una simulación educativa. " +
                "Configura GEMINI_API_KEY para usar Gemini en triage, SOAP, voz y farmacia.";

    @Value("${ai.gemini.api-key:}")
    private String geminiKey;

    @Value("${ai.gemini.model:gemini-3.8-flash}")
    private String geminiModel;

    @Value("${ai.gemini.fallback-models:gemini-3.6-flash,gemini-2.5-flash}")
    private String geminiFallbackModels;

    @Value("${ai.gemini.url:https://generativelanguage.googleapis.com/v1beta/models}")
    private String geminiBaseUrl;

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
                .build();
    }

    public boolean hayApiKey() {
        return hayGemini() || hayOpenAi();
    }

    public boolean hayGemini() {
        return geminiKey != null && !geminiKey.isBlank();
    }

    public boolean hayOpenAi() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String versionIa() {
        if (hayGemini()) return "Hospy AI — Gemini (" + geminiModel + ")";
        if (hayOpenAi()) return "Hospy AI — OpenAI (" + model + ")";
        return "Hospy AI — modo demo local";
    }

    public String estadoTranscripcion() {
        if (hayGemini()) return "Operativo — Gemini Audio";
        if (hayOpenAi()) return "Operativo — Whisper";
        return "Demo (navegador Web Speech API)";
    }

    public AiTranscripcionResponse transcribir(byte[] audio, String contentType) {
        if (hayGemini()) {
            Optional<String> gemini = transcribirConGemini(audio, contentType);
            if (gemini.isPresent() && !gemini.get().isBlank()) {
                return new AiTranscripcionResponse(gemini.get().trim(), false,
                        "Transcripción Gemini lista para revisión médica.");
            }
        }
        if (!hayOpenAi()) {
            return new AiTranscripcionResponse("", true,
                    "Configura GEMINI_API_KEY para transcribir el audio. También puedes usar la voz del navegador.");
        }
        String boundary = "HospyBoundary" + UUID.randomUUID();
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            agregarParte(body, boundary, "model", "whisper-1");
            agregarParte(body, boundary, "language", "es");
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
                return new AiTranscripcionResponse("", true, "Whisper no pudo transcribir el audio. Revisa la conexión y vuelve a intentar.");
            }
            String texto = mapper.readTree(response.body()).path("text").asText("");
            return new AiTranscripcionResponse(texto, false, "Transcripción Whisper lista para revisión médica.");
        } catch (Exception ex) {
            log.warn("Error en transcripcion de audio: {}", ex.getMessage());
            return new AiTranscripcionResponse("", true, "No se pudo completar la transcripción. Revisa la conexión y vuelve a intentar.");
        }
    }

    private void agregarParte(ByteArrayOutputStream body, String boundary, String nombre, String valor) throws Exception {
        body.write(("--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"" + nombre + "\"\r\n\r\n" +
                valor + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    /* ============ Llamada genérica al LLM (Gemini primero, OpenAI de respaldo) ============== */
    private Optional<String> llamarLlm(String systemPrompt, String userPrompt) {
        if (hayGemini()) {
            Optional<String> gemini = llamarGemini(systemPrompt, userPrompt, null, null, true);
            if (gemini.isPresent()) return gemini;
        }
        if (hayOpenAi()) return llamarOpenAi(systemPrompt, userPrompt);
        return Optional.empty();
    }

    private Optional<String> llamarOpenAi(String systemPrompt, String userPrompt) {
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
            return Optional.ofNullable(limpiarJson(content));
        } catch (Exception ex) {
            log.warn("Excepcion en llamada OpenAI: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> llamarGemini(String systemPrompt, String userPrompt, String mimeType, String base64, boolean json) {
        if (!hayGemini()) return Optional.empty();
        List<String> modelos = modelosGemini();
        for (String modelo : modelos) {
            Optional<String> out = llamarGeminiModelo(modelo, systemPrompt, userPrompt, mimeType, base64, json);
            if (out.isPresent()) return out;
        }
        return Optional.empty();
    }

    private List<String> modelosGemini() {
        List<String> modelos = new ArrayList<>();
        agregarModelo(modelos, "gemini-3.8-flash");
        agregarModelo(modelos, geminiModel);
        if (geminiFallbackModels != null) {
            for (String m : geminiFallbackModels.split(",")) agregarModelo(modelos, m);
        }
        agregarModelo(modelos, "gemini-3.6-flash");
        agregarModelo(modelos, "gemini-2.5-flash");
        return modelos;
    }

    private static void agregarModelo(List<String> modelos, String modelo) {
        if (modelo == null) return;
        String t = modelo.trim();
        if (t.isBlank() || modeloRetirado(t)) return;
        if (modelos.stream().noneMatch(t::equalsIgnoreCase)) modelos.add(t);
    }

    private static boolean modeloRetirado(String modelo) {
        String t = modelo.toLowerCase();
        return t.contains("gemini-2.0") || t.contains("gemini-1.5-flash-latest");
    }

    private Optional<String> llamarGeminiModelo(String modelo, String systemPrompt, String userPrompt,
                                               String mimeType, String base64, boolean json) {
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
            body.put("generationConfig", gen);

            String url = geminiBaseUrl + "/" + modelo + ":generateContent?key=" + geminiKey;
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                String snippet = resp.body() == null ? "" : resp.body().substring(0, Math.min(280, resp.body().length()));
                log.warn("Gemini {} status={} body={}", modelo, resp.statusCode(), snippet);
                return Optional.empty();
            }
            Optional<String> texto = extraerTextoGemini(resp.body()).map(this::limpiarJson);
            if (texto.isEmpty()) {
                log.warn("Gemini {} respondió 200 pero sin texto útil", modelo);
            }
            return texto;
        } catch (Exception ex) {
            log.warn("Excepción Gemini {}: {}", modelo, ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> transcribirConGemini(byte[] audio, String contentType) {
        String mime = (contentType == null || contentType.isBlank()) ? "audio/webm" : contentType.split(";")[0].trim();
        String prompt = "Transcribe fielmente este audio clínico en español. Devuelve solo el texto hablado, sin comentarios.";
        return llamarGemini(prompt, "Transcribe el audio.", mime, Base64.getEncoder().encodeToString(audio), false);
    }

    private Optional<String> extraerTextoGemini(String raw) {
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

    private String limpiarJson(String content) {
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
                Eres un clínico virtual experto en triaje de un portal hospitalario.
                Analiza TODO: el texto del paciente, la foto si existe y la pista de los datasets locales.
                Debes identificar la parte del cuerpo, el tipo de molestia (dolor, hinchazón, herida, erupción, trauma, infección)
                y la especialidad más coherente (Traumatología, Dermatología, Medicina General, Cardiología, etc.).
                Si hay foto, DESCRÍBELA: zona, color, hinchazón, herida, moretón, pus, asimetría. Nunca digas que no hay foto.
                No eres un servicio de urgencias validado. No inventes signos que no veas ni se mencionen.
                Devuelve UNICAMENTE JSON con esta estructura exacta:
                {"severidad":"Rojo|Naranja|Amarillo|Verde|Azul","escala":"ESI/MTS 5 niveles","nivelEsi":1,
                 "especialidadRecomendada":"...","prioridad":1,
                 "hallazgos":["h1","h2"],"recomendaciones":"...","resumen":"2-4 oraciones concretas"}
                """;
        final String user = """
                Paciente describe:
                - Síntomas: %s
                - Duración: %s
                - Antecedentes: %s
                - Edad: %s
                - Foto adjunta: %s
                %s
                Escribe hallazgos específicos (por ejemplo: "edema en dorso de la mano", "posible contusión") y un resumen claro.
                """.formatted(
                req.getSintomas(),
                nvl(req.getDuracion()),
                nvl(req.getAntecedentes()),
                req.getEdadPaciente() == null ? "no informado" : req.getEdadPaciente().toString(),
                hayFoto ? "sí, obsérvala con detalle" : "no",
                "Referencia datasets ESI/MTS: severidad=" + local.getSeveridad()
                        + ", ESI=" + local.getNivelEsi()
                        + ", especialidad=" + local.getEspecialidadRecomendada()
                        + ", hallazgos=[" + String.join("; ", nvlList(local.getHallazgos())) + "]. "
                        + "Úsala como pista; si el texto o la foto contradicen, corrige.");

        Optional<String> llm = hayFoto && hayGemini()
                ? llamarGemini(system, user, mimeDeDataUrl(req.getImagenBase64()), fotoB64, true)
                : llamarLlm(system, user);
        if (llm.isEmpty()) {
            if (hayFoto) {
                List<String> h = new ArrayList<>(nvlList(local.getHallazgos()));
                h.add("Gemini no respondió; se usó el dataset ESI/MTS y la foto quedó registrada.");
                local.setHallazgos(h);
            }
            return local;
        }
        try {
            AiTriageResponse r = mapper.readValue(llm.get(), AiTriageResponse.class);
            r.setModoDemo(false);
            normalizarTriage(r, local);
            return r;
        } catch (Exception ex) {
            log.warn("No se pudo leer JSON de triage Gemini: {}", ex.getMessage());
            return local;
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
    public AiDermatologiaResponse dermatologia(AiDermatologiaRequest req) {
        boolean hayImg = req.getImagenBase64() != null && !req.getImagenBase64().isBlank();
        boolean hayTxt = (req.getDescripcion() != null && !req.getDescripcion().isBlank())
                || (req.getSintomasAsociados() != null && !req.getSintomasAsociados().isBlank());
        if (!hayImg && !hayTxt) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Describe la lesión o sube una foto de evidencia.");
        }
        AiDermatologiaResponse local = datasets.dermatologia(req);
        final String system = """
                Eres un dermatólogo y traumatólogo virtual que revisa lesiones, inflamación y fotos clínicas.
                Analiza la foto (si existe) y el texto. Describe lo que ves: parte del cuerpo, hinchazón, color,
                herida, moretón, erupción, calor aparente, pus, uñas, asimetría.
                Relaciona con el dataset HAM10000 / PAD-UFES / ExtraTrees, pero corrígelo si la foto no coincide
                (una mano hinchada no es "sin foto" ni "dermatitis inespecífica" si se ve edema o trauma).
                No diagnostiques de forma definitiva. Responde SOLO JSON:
                {"nivelRiesgo":"BAJO|MEDIO|ALTO|CRITICO","scoreRiesgo":0.0,
                 "diagnosticosDiferenciales":["d1","d2"],"caracteristicasObservadas":["c1","c2"],
                 "recomendaciones":"...","advertencia":"No sustituye consulta presencial."}
                """;
        String user = userVisionPiel(req) + "\nReferencia ExtraTrees + HAM10000/PAD-UFES: riesgo="
                + local.getNivelRiesgo() + ", score=" + local.getScoreRiesgo()
                + ", diferenciales=[" + String.join("; ", nvlList(local.getDiagnosticosDiferenciales()))
                + "]. Si hay foto, descríbela y corrige esta pista si no coincide.";
        Optional<String> llm;
        if (hayImg && hayGemini()) {
            llm = llamarGemini(system, user, mimeDeDataUrl(req.getImagenBase64()), extraerBase64(req.getImagenBase64()), true);
        } else {
            llm = llamarLlm(system, user);
        }
        if (llm.isEmpty()) return local;
        try {
            AiDermatologiaResponse r = mapper.readValue(llm.get(), AiDermatologiaResponse.class);
            r.setModoDemo(false);
            return fusionarPiel(r, local, hayImg);
        } catch (Exception ex) {
            log.warn("No se pudo leer JSON de dermatología Gemini: {}", ex.getMessage());
            return local;
        }
    }

    private AiDermatologiaResponse dermatologiaMock(AiDermatologiaRequest req) {
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
        return AiDermatologiaResponse.builder()
                .nivelRiesgo(riesgo).scoreRiesgo(score)
                .diagnosticosDiferenciales(dd).caracteristicasObservadas(car)
                .recomendaciones(MODO_DEMO_SIN_API_KEY +
                        " Aplica protección solar FPS 50+ y consulta dermatología presencial (biopsia si persiste).")
                .advertencia("ADVERTENCIA: Este pre-diagnóstico NO sustituye consulta con dermatólogo.")
                .modoDemo(true).build();
    }

    /* ============ 3. Escriba SOAP médico ============== */
    public AiSoapResponse soap(AiSoapRequest req) {
        final String system = """
                Estructura el texto proporcionado como un borrador SOAP para revisión médica.
                No eres un sistema NER clínico validado. No inventes hallazgos, signos vitales, diagnósticos ni planes.
                Para datos ausentes, escribe "No informado". El profesional debe revisar y firmar el borrador.
                Devuelve JSON con esta estructura:
                {"subjetivo":"...(S)","objetivo":"...(O signos/constantes)","apreciacion":"...(A impresión diagnóstica)",
                 "plan":"...(P: conducta, estudios, manejo)","diagnosticoPresuntivo":"...","procedimientosSugeridos":"..."}
                """;
        String user = "Nombre paciente: " + nvl(req.getNombrePaciente()) +
                " | Motivo inicial: " + nvl(req.getMotivoInicial()) +
                " | Transcripción consulta:\n" + req.getTranscripcionConsulta();

        Optional<String> llm = llamarLlm(system, user);
        if (llm.isEmpty()) return soapMock(req);
        try {
            AiSoapResponse r = mapper.readValue(llm.get(), AiSoapResponse.class);
            r.setModoDemo(false);
            return r;
        } catch (Exception ex) {
            return soapMock(req);
        }
    }

    private AiSoapResponse soapMock(AiSoapRequest req) {
        String t = req.getTranscripcionConsulta();
        return AiSoapResponse.builder()
                .subjetivo("Paciente refiere: " + (t.length() > 300 ? t.substring(0, 300) + "..." : t))
                .objetivo("Signos vitales pendientes de registrar (modo demo). Piel: cálida y húmeda, mucosas rosadas.")
                .apreciacion("Cuadro probable de origen inespecífico (modo demo), correlacionar con examen físico completo.")
                .plan(MODO_DEMO_SIN_API_KEY +
                        " 1) Toma de constantes completas. 2) Paraclínicos según impresión. 3) Reevaluación en 48h. 4) Firma médica.")
                .diagnosticoPresuntivo("Diagnóstico diferencial pendiente de confirmación")
                .procedimientosSugeridos("-")
                .modoDemo(true).build();
    }

    /* ============ 4. Interacciones Fármaco-Alimento (GNN) ============== */
    public AiInteraccionResponse interacciones(AiInteraccionRequest req) {
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

        Optional<String> llm = llamarLlm(system, user);
        if (llm.isEmpty()) return datasets.interacciones(req);
        try {
            AiInteraccionResponse r = mapper.readValue(llm.get(), AiInteraccionResponse.class);
            r.setModoDemo(false);
            return r;
        } catch (Exception ex) {
            return interaccionesMock(req);
        }
    }

    private AiInteraccionResponse interaccionesMock(AiInteraccionRequest req) {
        List<AiInteraccionResponse.AlertaInteraccion> alertas = new ArrayList<>();
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
            alertas.add(AiInteraccionResponse.AlertaInteraccion.builder()
                    .elementos("Warfarina + alimentos ricos en vitamina K").tipo("FARMACO_ALIMENTO")
                    .severidad("GRAVE").mecanismo("Antagonismo en ciclo reducción vitamina K epóxido")
                    .consecuencia("Pérdida de efecto anticoagulante; riesgo de TEV/ACV").accion("Constancia diaria en verduras; INR semanal")
                    .build());
            recoms.add("Mantener porción diaria constante de vegetales verdes");
        }
        if (meds.stream().anyMatch(m -> m.contains("estatina") || m.contains("atorvasta") || m.contains("simvasta"))
                && dieta.stream().anyMatch(d -> d.contains("toronja") || d.contains("pomelo") || d.contains("jugo de toronja"))) {
            riesgo = "MEDIO";
            alertas.add(AiInteraccionResponse.AlertaInteraccion.builder()
                    .elementos("Estatinas + Toronja (pomelo)").tipo("FARMACO_ALIMENTO")
                    .severidad("MODERADA").mecanismo("Inhibición irreversible CYP3A4 y OATP1B1")
                    .consecuencia("Aumento de exposición sistémica x5; riesgo mialgia/rabdomiolisis")
                    .accion("Sustituir toronja por naranja dulce o mandarina").build());
        }
        if (meds.stream().anyMatch(m -> m.contains("tetraciclina") || m.contains("ciprofloxacino") || m.contains("fluoroquinolona"))
                && dieta.stream().anyMatch(d -> d.contains("leche") || d.contains("lácteo") || d.contains("queso") || d.contains("yogurt") || d.contains("calcio"))) {
            if (!riesgo.equals("ALTO")) riesgo = "MEDIO";
            alertas.add(AiInteraccionResponse.AlertaInteraccion.builder()
                    .elementos("Fluoroquinolona/Tetraciclina + Lácteos/Ca").tipo("FARMACO_ALIMENTO")
                    .severidad("MODERADA").mecanismo("Quelación catiónica bivalente; insolubilidad")
                    .consecuencia("Biodisponibilidad reducida ~50%; falla en tratamiento").accion("Separar 2h antes / 4h después").build());
        }
        if (meds.stream().anyMatch(m -> m.contains("ibuprofeno") || m.contains("naproxeno") || m.contains("ainec") || m.contains("aspirina"))
                && dieta.stream().anyMatch(d -> d.contains("alcohol") || d.contains("cerveza") || d.contains("vino"))) {
            if (!riesgo.equals("ALTO")) riesgo = "MEDIO";
            alertas.add(AiInteraccionResponse.AlertaInteraccion.builder()
                    .elementos("AINEs + Alcohol").tipo("FARMACO_ALIMENTO")
                    .severidad("MODERADA").mecanismo("Sinergia lesiva mucosa gástrica + alteración hemostasia")
                    .consecuencia("Ulcera péptica / hemorragia digestiva alta")
                    .accion("Suspender alcohol; protector gástrico si > 5 días de AINE").build());
        }
        if (alertas.isEmpty()) {
            alertas.add(AiInteraccionResponse.AlertaInteraccion.builder()
                    .elementos("Evaluación combinada").tipo("GENERAL")
                    .severidad("INFORMATIVA").mecanismo("No se detectaron interacciones graves por palabras clave")
                    .consecuencia("Perfil bajo de interacciones en modo demo")
                    .accion(MODO_DEMO_SIN_API_KEY).build());
        }
        return AiInteraccionResponse.builder()
                .nivelRiesgoGlobal(riesgo).alertas(alertas)
                .recomendacionesDieteticas(recoms)
                .resumen("GNN-demo evaluó " + meds.size() + " fármacos y " + dieta.size() + " alimentos. " +
                        (alertas.size()) + " alerta(s) detectada(s).")
                .modoDemo(true).build();
    }

    /* ============ 5. Triangulación precios medicamentos (Isolation Forest + Prophet) ============== */
    public AiPreciosResponse precios(AiPreciosRequest req) {
        final String system = """
                Eres un modelo IsolationForest + Prophet de triangulación de precios farmacéuticos.
                Responde JSON: {"precioEsperadoMin":12.5,"precioEsperadoMax":38.4,"precioPromedio":25.0,
                 "evaluacionSobreprecio":"DENTRO_RANGO|SOBREPRECIO_BAJO|SOBREPRECIO_ALTO|DESABASTECIMIENTO_ARTIFICIAL",
                 "porcentajeDesviacion":12.0,
                 "comparativa":[{"nombre":"Farmacia Cruz Verde","precio":25.9,"disponibilidad":"En stock","url":"https://..."}],
                 "alertas":["a1"],"recomendacion":"..."}
                """;
        String user = "Medicamento: " + req.getMedicamento() +
                " | Presentación: " + nvl(req.getPresentacion()) +
                " | Precio reportado por usuario: " + (req.getPrecioReportado() == null ? "no informado" : req.getPrecioReportado()) +
                " | Ciudad: " + nvl(req.getCiudad());

        Optional<String> llm = llamarLlm(system, user);
        if (llm.isEmpty()) return preciosMock(req);
        try {
            AiPreciosResponse r = mapper.readValue(llm.get(), AiPreciosResponse.class);
            r.setModoDemo(false);
            return r;
        } catch (Exception ex) {
            return preciosMock(req);
        }
    }

    private AiPreciosResponse preciosMock(AiPreciosRequest req) {
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

        List<AiPreciosResponse.ComparativaFarmacia> comp = List.of(
                AiPreciosResponse.ComparativaFarmacia.builder()
                        .nombre("Cruz Verde").precio(base * 1.05).disponibilidad("En stock").url("https://www.cruzverde.com.co").build(),
                AiPreciosResponse.ComparativaFarmacia.builder()
                        .nombre("Farmatodo").precio(base * 0.98).disponibilidad("En stock").url("https://www.farmatodo.com.co").build(),
                AiPreciosResponse.ComparativaFarmacia.builder()
                        .nombre("Droguería Cafam").precio(base * 0.86).disponibilidad("3 disponibles").url("https://www.drogueriascafam.com.co").build(),
                AiPreciosResponse.ComparativaFarmacia.builder()
                        .nombre("Locatel").precio(base * 1.18).disponibilidad("En stock").url("https://www.locatelcolombia.com").build()
        );

        List<String> alertas = new ArrayList<>();
        String eval = "DENTRO_RANGO";
        double desv = 0.0;
        if (req.getPrecioReportado() != null) {
            desv = ((req.getPrecioReportado() - pmed) / pmed) * 100.0;
            if (desv > 45) { eval = "SOBREPRECIO_ALTO"; alertas.add("⚠️ Sobreprecio detectado (" + Math.round(desv) + "% > promedio)"); }
            else if (desv > 15) { eval = "SOBREPRECIO_BAJO"; alertas.add("ℹ️ Precio por encima del percentil 75"); }
            if (desv < -35) { eval = "DESABASTECIMIENTO_ARTIFICIAL"; alertas.add("🚨 Precio sospechosamente bajo: posible oferta sin stock (hoarding especulativo)"); }
        }
        alertas.add("Modo demo: compara tu precio reportado vs 4 cadenas nacionales.");

        return AiPreciosResponse.builder()
                .precioEsperadoMin(Math.round(min * 100.0) / 100.0)
                .precioEsperadoMax(Math.round(max * 100.0) / 100.0)
                .precioPromedio(Math.round(pmed * 100.0) / 100.0)
                .evaluacionSobreprecio(eval)
                .porcentajeDesviacion(Math.round(desv * 100.0) / 100.0)
                .comparativa(comp).alertas(alertas)
                .recomendacion(MODO_DEMO_SIN_API_KEY +
                        " Compara en al menos 3 cadenas, verifica principio activo genérico igual y lote de invima. Reporta sobreprecios a SuperSalud/INVIMA.")
                .modoDemo(true).build();
    }

    private static String nvl(String s) { return s == null || s.isBlank() ? "no informado" : s; }

    private static List<String> nvlList(List<String> in) {
        return in == null ? new ArrayList<>() : new ArrayList<>(in);
    }

    private static String userVisionPiel(AiDermatologiaRequest req) {
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

    private static void normalizarTriage(AiTriageResponse r, AiTriageResponse local) {
        if (r.getHallazgos() == null || r.getHallazgos().isEmpty()) {
            r.setHallazgos(nvlList(local.getHallazgos()));
        } else {
            List<String> h = nvlList(r.getHallazgos());
            h.add(0, "Análisis Gemini + datasets ESI/MTS.");
            r.setHallazgos(h);
        }
        if (r.getEspecialidadRecomendada() == null || r.getEspecialidadRecomendada().isBlank()) {
            r.setEspecialidadRecomendada(local.getEspecialidadRecomendada());
        }
        if (r.getSeveridad() == null || r.getSeveridad().isBlank()) r.setSeveridad(local.getSeveridad());
        else r.setSeveridad(capitalizarSeveridad(r.getSeveridad()));
        if (r.getNivelEsi() == null) r.setNivelEsi(local.getNivelEsi());
        if (r.getPrioridad() == null) r.setPrioridad(local.getPrioridad());
        if (r.getEscala() == null || r.getEscala().isBlank()) r.setEscala("ESI/MTS 5 niveles + Gemini");
        if (r.getResumen() == null || r.getResumen().isBlank()) r.setResumen(local.getResumen());
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

    private static AiDermatologiaResponse fusionarPiel(AiDermatologiaResponse gemini, AiDermatologiaResponse local, boolean hayImg) {
        List<String> car = nvlList(gemini.getCaracteristicasObservadas());
        if (car.stream().noneMatch(c -> c.toLowerCase().contains("gemini"))) {
            car.add(0, hayImg
                    ? "Gemini analizó la foto junto con el texto y los datasets de piel."
                    : "Gemini analizó el texto con apoyo de HAM10000 / PAD-UFES.");
        }
        for (String c : nvlList(local.getCaracteristicasObservadas())) {
            if (car.stream().noneMatch(x -> x.equalsIgnoreCase(c))) car.add(c);
        }
        gemini.setCaracteristicasObservadas(car);
        List<String> dx = nvlList(gemini.getDiagnosticosDiferenciales());
        if (dx.isEmpty()) dx.addAll(nvlList(local.getDiagnosticosDiferenciales()));
        gemini.setDiagnosticosDiferenciales(dx);
        if (gemini.getNivelRiesgo() == null || gemini.getNivelRiesgo().isBlank()) {
            gemini.setNivelRiesgo(local.getNivelRiesgo());
        } else {
            gemini.setNivelRiesgo(gemini.getNivelRiesgo().trim().toUpperCase());
        }
        if (gemini.getScoreRiesgo() == null) gemini.setScoreRiesgo(local.getScoreRiesgo());
        if (gemini.getAdvertencia() == null || gemini.getAdvertencia().isBlank()) {
            gemini.setAdvertencia("Orientación automática. No sustituye consulta presencial.");
        }
        return gemini;
    }
}
