package com.usuario.Medico.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.usuario.Medico.dto.*;
import jakarta.annotation.PostConstruct;
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

    @PostConstruct
    void logEstadoGemini() {
        log.info("Gemini: {} modelo={}", hayGemini() ? "clave presente" : "SIN CLAVE", geminiModel);
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
                Eres un clínico experto. Usa TODO tu conocimiento médico, no solo la pista interna.
                Analiza el relato y la foto si existe. Identifica zona, tipo de molestia y especialidad.
                NUNCA menciones datasets, modelos, ExtraTrees, HAM10000, ESI, MedQA, Hugging Face ni precisión.
                El paciente solo debe ver severidad y el porqué clínico.
                Devuelve UNICAMENTE JSON:
                {"severidad":"Rojo|Naranja|Amarillo|Verde|Azul","escala":"orientación 5 niveles","nivelEsi":1,
                 "especialidadRecomendada":"...","prioridad":1,
                 "hallazgos":[],"recomendaciones":"...","resumen":"2-4 oraciones: qué pasa y por qué ese nivel"}
                """;
        final String user = """
                Paciente describe:
                - Síntomas: %s
                - Duración: %s
                - Antecedentes: %s
                - Edad: %s
                - Foto adjunta: %s
                Pista interna (no la cites): severidad=%s, especialidad=%s.
                Escribe un resumen humano: qué ves o infieres y por qué esa severidad.
                """.formatted(
                req.getSintomas(),
                nvl(req.getDuracion()),
                nvl(req.getAntecedentes()),
                req.getEdadPaciente() == null ? "no informado" : req.getEdadPaciente().toString(),
                hayFoto ? "sí, obsérvala con detalle" : "no",
                local.getSeveridad(),
                local.getEspecialidadRecomendada());

        Optional<String> llm = consultarGemini(system, user, req.getImagenBase64());
        if (llm.isEmpty()) return limpiarTriage(local, req.getSintomas());
        try {
            AiTriageResponse r = mapper.readValue(llm.get(), AiTriageResponse.class);
            r.setModoDemo(false);
            normalizarTriage(r, local);
            return limpiarTriage(r, req.getSintomas());
        } catch (Exception ex) {
            log.warn("No se pudo leer JSON de triage Gemini: {}", ex.getMessage());
            return limpiarTriage(local, req.getSintomas());
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
                Eres un médico experto en piel, infección y trauma. Usa TODO tu conocimiento, no solo la pista interna.
                Mira la foto y el relato. Di qué tan alto es el riesgo y POR QUÉ (color, hinchazón, herida, pus, dolor).
                NUNCA menciones datasets, modelos, ExtraTrees, HAM10000, precisión, bosqué ni Hugging Face.
                No listes porcentajes de un clasificador. Responde SOLO JSON:
                {"nivelRiesgo":"BAJO|MEDIO|ALTO|CRITICO","scoreRiesgo":0.0,
                 "explicacion":"2-4 oraciones claras: qué ves y por qué ese nivel",
                 "diagnosticosDiferenciales":[],"caracteristicasObservadas":[],
                 "recomendaciones":"","advertencia":""}
                """;
        String user = userVisionPiel(req) + "\nPista interna (no la cites): riesgo="
                + local.getNivelRiesgo() + ". Usa tu criterio si la foto o el texto dicen otra cosa.";
        Optional<String> llm = consultarGemini(system, user, req.getImagenBase64());
        if (llm.isEmpty()) return limpiarPiel(local, req);
        try {
            AiDermatologiaResponse r = mapper.readValue(llm.get(), AiDermatologiaResponse.class);
            r.setModoDemo(false);
            return limpiarPiel(fusionarPiel(r, local), req);
        } catch (Exception ex) {
            log.warn("No se pudo leer JSON de dermatología Gemini: {}", ex.getMessage());
            return limpiarPiel(local, req);
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

    /* ============ Admin: interpretar alta de médico/paciente ============== */
    public AiAdminOperacionResponse adminOperacion(AiAdminOperacionRequest req) {
        final String system = """
                Eres el asistente de administración del hospital Hospy.
                Solo puedes proponer CREAR_MEDICO o CREAR_PACIENTE o INFO.
                No inventes módulos nuevos de la aplicación. No des de baja usuarios.
                Especialidades válidas: Medicina General, Cardiología, Neurología, Dermatología,
                Traumatología, Pediatría, Ginecología, Oftalmología, Otorrinolaringología,
                Gastroenterología, Endocrinología, Urología, Psiquiatría, Neumología,
                Medicina Interna, Ortopedia, Reumatología, Oncología, Nefrología, Infectología.
                Devuelve SOLO JSON:
                {"accion":"CREAR_MEDICO|CREAR_PACIENTE|INFO","nombre":"...","email":"...@hospy.com",
                 "especialidad":"...","passwordSugerida":"Medico123","documento":"","telefono":"",
                 "mensaje":"qué vas a crear y por qué"}
                """;
        String user = "Instrucción del administrador: " + req.getInstruccion();
        Optional<String> llm = llamarLlm(system, user);
        if (llm.isEmpty()) return adminOperacionLocal(req.getInstruccion());
        try {
            AiAdminOperacionResponse r = mapper.readValue(llm.get(), AiAdminOperacionResponse.class);
            r.setModoDemo(false);
            if (r.getAccion() == null || r.getAccion().isBlank()) r.setAccion("INFO");
            r.setAccion(r.getAccion().trim().toUpperCase());
            if (!r.getAccion().equals("CREAR_MEDICO") && !r.getAccion().equals("CREAR_PACIENTE")) {
                r.setAccion("INFO");
            }
            return r;
        } catch (Exception ex) {
            return adminOperacionLocal(req.getInstruccion());
        }
    }

    private AiAdminOperacionResponse adminOperacionLocal(String instruccion) {
        String t = instruccion == null ? "" : instruccion.toLowerCase();
        if (t.contains("medico") || t.contains("médico") || t.contains("doctor")) {
            String esp = "Medicina General";
            if (t.contains("cardio")) esp = "Cardiología";
            else if (t.contains("neuro")) esp = "Neurología";
            else if (t.contains("derma") || t.contains("piel")) esp = "Dermatología";
            else if (t.contains("trauma") || t.contains("hueso")) esp = "Traumatología";
            else if (t.contains("pedia")) esp = "Pediatría";
            else if (t.contains("gine")) esp = "Ginecología";
            return AiAdminOperacionResponse.builder()
                    .accion("CREAR_MEDICO").especialidad(esp)
                    .passwordSugerida("Medico123")
                    .email("medico." + esp.toLowerCase().replace(" ", "") + "@hospy.com")
                    .nombre("Dr. " + esp)
                    .mensaje("Borrador local: crear médico de " + esp + ". Revisa nombre y correo antes de confirmar.")
                    .modoDemo(true).build();
        }
        if (t.contains("paciente")) {
            return AiAdminOperacionResponse.builder()
                    .accion("CREAR_PACIENTE").passwordSugerida("Paciente123")
                    .mensaje("Borrador local: crear paciente. Completa nombre, correo y documento.")
                    .modoDemo(true).build();
        }
        return AiAdminOperacionResponse.builder()
                .accion("INFO")
                .mensaje("Puedo crear un médico o un paciente. Ejemplo: «Crea un médico de pediatría llamado Ana Ruiz».")
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

    private Optional<String> consultarGemini(String system, String user, String imagenDataUrl) {
        String b64 = extraerBase64(imagenDataUrl);
        if (!b64.isBlank() && hayGemini()) {
            Optional<String> vision = llamarGemini(system, user, mimeDeDataUrl(imagenDataUrl), b64, true);
            if (vision.isPresent()) return vision;
        }
        return llamarLlm(system, user);
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
        if (r.getResumen() == null || r.getResumen().isBlank() || esJergaTecnica(r.getResumen())) {
            String sev = r.getSeveridad() == null ? "Verde" : r.getSeveridad();
            String esp = r.getEspecialidadRecomendada() == null ? "Medicina General" : r.getEspecialidadRecomendada();
            r.setResumen("Severidad " + sev + " hacia " + esp + " por lo que describes"
                    + (sintomas == null || sintomas.isBlank() ? "." : ": " + sintomas.trim() + "."));
        }
        if (r.getRecomendaciones() != null && esJergaTecnica(r.getRecomendaciones())) {
            r.setRecomendaciones("Si empeora, hay fiebre o el dolor es intenso, consulta de forma presencial.");
        }
        return r;
    }

    private static AiDermatologiaResponse limpiarPiel(AiDermatologiaResponse r, AiDermatologiaRequest req) {
        if (r.getNivelRiesgo() != null) r.setNivelRiesgo(r.getNivelRiesgo().trim().toUpperCase());
        r.setCaracteristicasObservadas(List.of());
        r.setDiagnosticosDiferenciales(List.of());
        r.setAdvertencia(null);
        r.setRecomendaciones(null);
        if (r.getExplicacion() == null || r.getExplicacion().isBlank() || esJergaTecnica(r.getExplicacion())) {
            r.setExplicacion(explicacionPiel(r.getNivelRiesgo(), req));
        }
        return r;
    }

    private static String explicacionPiel(String nivel, AiDermatologiaRequest req) {
        String n = nivel == null ? "MEDIO" : nivel.toUpperCase();
        String porque = switch (n) {
            case "CRITICO" -> "Hay signos de alarma (dolor fuerte, extensión rápida o posible infección grave). Hay que valorarlo ya.";
            case "ALTO" -> "La zona se ve inflamada o alterada de forma importante. Puede ser infección o lesión que no debe esperarse.";
            case "MEDIO" -> "Hay un cambio en la piel que conviene revisar, aunque no parece una emergencia inmediata.";
            default -> "El cambio parece leve. Vigílalo y consulta si empeora, hay fiebre o el dolor aumenta.";
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

    private static AiDermatologiaResponse fusionarPiel(AiDermatologiaResponse gemini, AiDermatologiaResponse local) {
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
