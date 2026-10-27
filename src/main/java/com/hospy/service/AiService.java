package com.hospy.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospy.dto.*;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    public AiTranscriptionResponse transcribir(byte[] audio, String contentType) {
        if (hayGemini()) {
            Optional<String> gemini = transcribirConGemini(audio, contentType);
            if (gemini.isPresent() && !gemini.get().isBlank()) {
                return new AiTranscriptionResponse(gemini.get().trim(), false,
                        "Transcripción Gemini lista para revisión médica.");
            }
        }
        if (!hayOpenAi()) {
            return new AiTranscriptionResponse("", true,
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
                return new AiTranscriptionResponse("", true, "Whisper no pudo transcribir el audio. Revisa la conexión y vuelve a intentar.");
            }
            String texto = mapper.readTree(response.body()).path("text").asText("");
            return new AiTranscriptionResponse(texto, false, "Transcripción Whisper lista para revisión médica.");
        } catch (Exception ex) {
            log.warn("Error en transcripcion de audio: {}", ex.getMessage());
            return new AiTranscriptionResponse("", true, "No se pudo completar la transcripción. Revisa la conexión y vuelve a intentar.");
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
        agregarModelo(modelos, "gemini-3.6-flash");
        agregarModelo(modelos, geminiModel);
        if (geminiFallbackModels != null) {
            for (String m : geminiFallbackModels.split(",")) agregarModelo(modelos, m);
        }
        agregarModelo(modelos, "gemini-3.8-flash");
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
                    .timeout(Duration.ofSeconds(Math.max(timeout, 30)))
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
                Eres el médico de orientación de Hospy. Analiza ESTE caso con tu criterio y con la foto si existe.
                La pista de datasets es solo una referencia: si el relato o la imagen dicen otra cosa, corrígela.
                Habla solo de lo que el paciente contó o de lo que se ve en la foto. No inventes falta de aire, dolor de pecho ni otros síntomas que no mencionó.
                El resumen debe tener 4 a 6 oraciones en español: qué refirió, desde cuándo, antecedentes, qué se aprecia en la foto si hay, por qué ese nivel y que agende ya la especialidad mientras espera.
                Las recomendaciones deben decir qué vigilar de SUS molestias y qué hacer hasta la cita.
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
                Pista interna de datasets, no la copies ni la cites: %s
                """.formatted(
                nvl(req.getSintomas()),
                nvl(req.getDuracion()),
                nvl(req.getAntecedentes()),
                req.getEdadPaciente() == null ? "no informado" : req.getEdadPaciente().toString(),
                hayFoto ? "sí, descríbela y úsala en el resumen" : "no",
                datasets.contextoTriageParaGemini(req));

        Optional<String> llm = consultarGemini(system, user, req.getImagenBase64());
        if (llm.isEmpty()) return orientarSinGemini(local, req);
        try {
            AiTriageResponse r = mapper.readValue(llm.get(), AiTriageResponse.class);
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
    public AiDermatologyResponse dermatologia(AiDermatologyRequest req) {
        boolean hayImg = req.getImagenBase64() != null && !req.getImagenBase64().isBlank();
        boolean hayTxt = (req.getDescripcion() != null && !req.getDescripcion().isBlank())
                || (req.getSintomasAsociados() != null && !req.getSintomasAsociados().isBlank());
        if (!hayImg && !hayTxt) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Describe la lesión o sube una foto de evidencia.");
        }
        AiDermatologyResponse local = datasets.dermatologia(req);
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
            AiDermatologyResponse r = mapper.readValue(llm.get(), AiDermatologyResponse.class);
            r.setModoDemo(false);
            return limpiarPiel(fusionarPiel(r, local), req);
        } catch (Exception ex) {
            log.warn("No se pudo leer JSON de dermatología Gemini: {}", ex.getMessage());
            return limpiarPiel(local, req);
        }
    }

    private AiDermatologyResponse dermatologiaMock(AiDermatologyRequest req) {
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
    public AiAdminOperationResponse adminOperacion(AiAdminOperationRequest req) {
        String instruccion = req.getInstruccion() == null ? "" : req.getInstruccion().trim();
        AiAdminOperationResponse previo = leerBorrador(req.getBorrador());
        if (debeReiniciarAlta(instruccion, previo)) previo = null;
        final String system = """
                Eres Hospy y registras médicos y pacientes. Lees español natural, aunque venga incompleto o desordenado.
                Ejemplos de cómo escribe el administrador, sin copiar estos nombres: "crea este paciente", "paciente: Ana López",
                "el documento es 1087782595", "gmail ana@gmail.com", "la contraseña es Hospy2026", "12 años de experiencia",
                "licencia MED-10", "teléfono 3001234567", "lunes a viernes de 8 a 12 y de 2 a 5".
                No inventes correo, contraseña, documento, teléfono, licencia, años, horario ni datos clínicos. Si no lo dijo, déjalo vacío.
                Un paciente también necesita fechaNacimiento en formato yyyy-MM-dd, genero, tipoSangre, direccion, ciudad, alergias, contactoEmergencia, telefonoEmergencia y observaciones.
                Si dice ninguna, no tiene o no aplica, guarda "Ninguna".
                Conserva el borrador. El mensaje nuevo solo agrega o corrige.
                Si ya dijo que es paciente, accion=CREAR_PACIENTE. Si es médico, doctor o especialista, accion=CREAR_MEDICO.
                Si pide cancelar, accion=INFO.
                Especialidades válidas: Medicina General, Cardiología, Neurología, Dermatología, Traumatología,
                Ortopedia, Pediatría, Ginecología, Oftalmología, Otorrinolaringología, Gastroenterología,
                Endocrinología, Urología, Psiquiatría, Neumología, Medicina Interna, Reumatología, Oncología,
                Nefrología, Infectología.
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
        String user = "Borrador actual:\n" + (previo == null ? "{}" : escribirBorrador(previo))
                + "\nMensaje del administrador:\n" + instruccion;
        Optional<String> llm = llamarLlm(system, user);
        AiAdminOperationResponse dichos = adminOperacionLocal(instruccion, null);
        if ("INFO".equalsIgnoreCase(dichos.getAccion()) && dichos.getMensaje() != null
                && dichos.getMensaje().startsWith("Listo.")) {
            dichos.setModoDemo(llm.isEmpty());
            return dichos;
        }
        AiAdminOperationResponse r = llm.flatMap(this::leerRespuestaAdmin).orElse(null);
        if (r == null) r = previo == null ? new AiAdminOperationResponse() : previo;
        else r = fusionarAlta(previo, r);
        aplicarDichos(r, dichos);
        r.setModoDemo(llm.isEmpty());
        return exigirDatosAlta(r);
    }

    private void aplicarDichos(AiAdminOperationResponse destino, AiAdminOperationResponse dichos) {
        if (dichos == null) return;
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
        if (dichos.getAnosExperiencia() != null) destino.setAnosExperiencia(dichos.getAnosExperiencia());
        if (!enBlanco(dichos.getAccion())) destino.setAccion(dichos.getAccion());
    }

    private Optional<AiAdminOperationResponse> leerRespuestaAdmin(String json) {
        try {
            return Optional.of(mapper.readValue(json, AiAdminOperationResponse.class));
        } catch (Exception ex) {
            log.warn("No se pudo leer el alta de Gemini: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private AiAdminOperationResponse leerBorrador(String borrador) {
        if (borrador == null || borrador.isBlank()) return null;
        try {
            return mapper.readValue(borrador, AiAdminOperationResponse.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private String escribirBorrador(AiAdminOperationResponse r) {
        try {
            return mapper.writeValueAsString(r);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private boolean esAltaNueva(String texto) {
        String t = texto == null ? "" : sinAcentoAdmin(texto).toLowerCase();
        boolean pideAlta = t.contains("crea") || t.contains("registrar") || t.contains("nuevo medico")
                || t.contains("nueva medica") || t.contains("nuevo paciente") || t.contains("nueva paciente");
        boolean diceQuien = t.contains("medic") || t.contains("doctor") || t.contains("especialista") || t.contains("paciente");
        return pideAlta && diceQuien;
    }

    private boolean debeReiniciarAlta(String texto, AiAdminOperationResponse previo) {
        String t = texto == null ? "" : sinAcentoAdmin(texto).toLowerCase();
        if (t.contains("cancela") || t.contains("olvidalo") || t.contains("empezar de nuevo") || t.contains("otra persona")) {
            return true;
        }
        if (previo == null || !esAltaNueva(texto)) return false;
        String nombre = extraerNombreAlta(texto);
        return nombre != null && !enBlanco(previo.getNombre())
                && !sinAcentoAdmin(nombre).equalsIgnoreCase(sinAcentoAdmin(previo.getNombre()));
    }

    private AiAdminOperationResponse fusionarAlta(AiAdminOperationResponse previo, AiAdminOperationResponse nuevo) {
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

    private AiAdminOperationResponse adminOperacionLocal(String instruccion, AiAdminOperationResponse previo) {
        AiAdminOperationResponse r = previo == null ? new AiAdminOperationResponse() : previo;
        String original = instruccion == null ? "" : instruccion.trim();
        String t = sinAcentoAdmin(original).toLowerCase();
        if (t.contains("cancela") || t.contains("olvidalo") || t.contains("empezar de nuevo")) {
            return AiAdminOperationResponse.builder().accion("INFO").modoDemo(true)
                    .mensaje("Listo. Dime si el alta es de un médico o de un paciente.").build();
        }
        if (t.contains("paciente")) r.setAccion("CREAR_PACIENTE");
        else if (t.contains("medic") || t.contains("doctor") || t.contains("especialista")) r.setAccion("CREAR_MEDICO");
        String nombre = extraerNombreAlta(original);
        if (nombre != null) r.setNombre(nombre);
        String email = extraerGrupo(original, "([\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,})");
        if (email != null) r.setEmail(email);
        String anos = extraerGrupo(t, "(?:experiencia\\s*(?:de)?\\s*)?(\\d{1,2})\\s*anos");
        if (anos == null) anos = extraerGrupo(t, "experiencia\\s*(?:de|:)?\\s*(\\d{1,2})");
        if (anos != null) r.setAnosExperiencia(Integer.parseInt(anos));
        String licencia = extraerGrupo(original, "(?i)licencia\\s*(?:es|numero|n[uú]mero|:)?\\s*[:#]?\\s*([A-Za-z0-9-]{3,30})");
        if (licencia != null) r.setNumeroLicencia(licencia);
        String documento = extraerGrupo(original, "(?i)(?:documento|c[eé]dula|cedula|dni)(?:\\s+de\\s+identidad)?\\s*(?:es|n[uú]mero|:)?\\s*[:#]?\\s*([A-Za-z0-9-]{5,20})");
        if (documento != null) r.setDocumento(documento);
        String telefono = extraerGrupo(original, "(?i)(?:tel[eé]fono|celular|cel|whatsapp)\\s*(?:es|:)?\\s*([0-9][0-9 \\-]{6,18})");
        if (telefono != null) r.setTelefono(telefono.replaceAll("[^0-9]", ""));
        String clave = extraerGrupo(original, "(?i)(?:clave|contrase[nñ]a|password)\\s*(?:es|:)?\\s*(\\S{6,40})");
        if (clave != null && !clave.equalsIgnoreCase("es")) r.setPasswordSugerida(clave);
        String esp = especialidadEnTexto(original);
        if (esp != null) r.setEspecialidad(esp);
        String sangre = extraerGrupo(original, "(?i)(?:sangre|rh|grupo sangu[ií]neo)\\s*(?:es|:)?\\s*(AB|A|B|O)\\s*([+-]|positivo|negativo)");
        if (sangre != null) {
            Matcher ms = Pattern.compile("(?i)(AB|A|B|O)\\s*([+-]|positivo|negativo)").matcher(original);
            if (ms.find()) {
                String signo = ms.group(2).startsWith("n") || ms.group(2).equals("-") ? "-" : "+";
                r.setTipoSangre(ms.group(1).toUpperCase() + signo);
            }
        }
        Matcher fecha = Pattern.compile("(\\d{1,2})[/-](\\d{1,2})[/-](\\d{4})").matcher(original);
        if (fecha.find()) {
            r.setFechaNacimiento(String.format("%s-%02d-%02d", fecha.group(3),
                    Integer.parseInt(fecha.group(2)), Integer.parseInt(fecha.group(1))));
        } else {
            String iso = extraerGrupo(original, "(\\d{4}-\\d{2}-\\d{2})");
            if (iso != null) r.setFechaNacimiento(iso);
        }
        if (t.matches(".*\\b(masculino|hombre)\\b.*")) r.setGenero("Masculino");
        else if (t.matches(".*\\b(femenino|mujer)\\b.*")) r.setGenero("Femenino");
        completarHorario(r, original);
        r.setModoDemo(true);
        return r;
    }

    private String extraerNombreAlta(String original) {
        String nombre = extraerGrupo(original, "(?i)(?:paciente|m[eé]dico|doctor)\\s*[:\\-]\\s*([\\p{L}][\\p{L} .'-]{2,80})");
        if (nombre == null) {
            nombre = extraerGrupo(original, "(?i)(?:con el nombre|nombre|llamad[oa])\\s*[:\\-]?\\s*([\\p{L}][\\p{L} .'-]{2,80})");
        }
        if (nombre == null) return null;
        nombre = nombre.split("(?i)\\s+(con|correo|email|gmail|documento|tel[eé]fono|celular|licencia|especialidad|clave|contrase)\\b")[0].trim();
        nombre = nombre.replaceAll("[,.:;]+$", "").trim();
        return nombre.isBlank() ? null : nombre;
    }

    private String especialidadEnTexto(String texto) {
        if (texto == null) return null;
        String n = sinAcentoAdmin(texto).toLowerCase();
        String mejor = null;
        int mejorLargo = 0;
        for (String especialidad : ESPECIALIDADES_ADMIN) {
            String e = sinAcentoAdmin(especialidad).toLowerCase();
            if (n.contains(e) && e.length() > mejorLargo) {
                mejor = especialidad;
                mejorLargo = e.length();
            }
        }
        return mejor;
    }

    private void completarHorario(AiAdminOperationResponse r, String original) {
        String t = sinAcentoAdmin(original).toLowerCase();
        if (enBlanco(r.getDiasAtencion())) {
            if (t.contains("lunes a domingo") || t.contains("todos los dias")) r.setDiasAtencion("1,2,3,4,5,6,7");
            else if (t.contains("lunes a sabado")) r.setDiasAtencion("1,2,3,4,5,6");
            else if (t.contains("lunes a viernes") || t.contains("entre semana")) r.setDiasAtencion("1,2,3,4,5");
        }
        Matcher rangos = Pattern.compile("(\\d{1,2})(?::(\\d{2}))?\\s*(?:a|hasta|-)\\s*(\\d{1,2})(?::(\\d{2}))?").matcher(t);
        int n = 0;
        while (rangos.find() && n < 2) {
            int desde = Integer.parseInt(rangos.group(1));
            int hasta = Integer.parseInt(rangos.group(3));
            if (desde > 23 || hasta > 23) {
                n++;
                continue;
            }
            if (n == 1 && desde <= 6) desde += 12;
            if (n == 1 && hasta <= 7) hasta += 12;
            String ini = String.format("%02d:%s", desde, rangos.group(2) == null ? "00" : rangos.group(2));
            String fin = String.format("%02d:%s", hasta, rangos.group(4) == null ? "00" : rangos.group(4));
            if (n == 0 && enBlanco(r.getHoraInicio())) {
                r.setHoraInicio(ini);
                r.setHoraFin(fin);
            } else if (n == 1 && enBlanco(r.getHoraInicio2())) {
                r.setHoraInicio2(ini);
                r.setHoraFin2(fin);
            }
            n++;
        }
        if (!enBlanco(r.getDiasAtencion()) || !enBlanco(r.getHoraInicio())) {
            r.setHorario(textoHorario(r));
        }
    }

    private String textoHorario(AiAdminOperationResponse r) {
        String dias = switch (nvl(r.getDiasAtencion())) {
            case "1,2,3,4,5" -> "lunes a viernes";
            case "1,2,3,4,5,6" -> "lunes a sábado";
            case "1,2,3,4,5,6,7" -> "lunes a domingo";
            default -> "días " + nvl(r.getDiasAtencion());
        };
        String horas = nvl(r.getHoraInicio()) + " a " + nvl(r.getHoraFin());
        if (!enBlanco(r.getHoraInicio2())) horas += " y " + r.getHoraInicio2() + " a " + r.getHoraFin2();
        return dias + ", " + horas;
    }

    private AiAdminOperationResponse exigirDatosAlta(AiAdminOperationResponse r) {
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
        String esp = especialidadEnTexto(r.getEspecialidad());
        if (esp == null) esp = normalizarEspecialidad(r.getEspecialidad());
        if (esp != null) r.setEspecialidad(esp);
        boolean medico = "CREAR_MEDICO".equals(accion);
        if ("PREGUNTAR".equals(accion)) medico = !enBlanco(r.getEspecialidad()) || !enBlanco(r.getNumeroLicencia());
        if ("CREAR_PACIENTE".equals(accion)) medico = false;
        List<String> faltan = new ArrayList<>();
        if (enBlanco(r.getNombre())) faltan.add("el nombre completo");
        if (enBlanco(r.getEmail()) || !r.getEmail().contains("@")) faltan.add("el correo");
        if (enBlanco(r.getPasswordSugerida()) || r.getPasswordSugerida().length() < 6) faltan.add("la contraseña");
        if (medico) {
            r.setAccion("CREAR_MEDICO");
            if (esp == null) {
                r.setEspecialidad(null);
                faltan.add("la especialidad");
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
        if (!faltan.isEmpty()) {
            r.setAccion("PREGUNTAR");
            String tipo = medico ? "médico" : "paciente";
            if (enBlanco(r.getNombre())) {
                r.setMensaje("Para registrar al " + tipo + " me falta " + juntarFaltantes(faltan) + ". Puedes escribirlos como quieras.");
            } else {
                r.setMensaje(r.getNombre() + " ya quedó anotado como " + tipo + ". Para registrarlo me falta "
                        + juntarFaltantes(faltan) + ". Puedes escribirlos como quieras.");
            }
            return r;
        }
        if (medico) {
            r.setHorario(textoHorario(r));
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

    private String juntarFaltantes(List<String> faltan) {
        if (faltan.size() == 1) return faltan.get(0);
        return String.join(", ", faltan.subList(0, faltan.size() - 1)) + " y " + faltan.get(faltan.size() - 1);
    }

    private String normalizarEspecialidad(String valor) {
        if (valor == null || valor.isBlank()) return null;
        String n = sinAcentoAdmin(valor).toLowerCase();
        String mejor = null;
        for (String especialidad : ESPECIALIDADES_ADMIN) {
            String e = sinAcentoAdmin(especialidad).toLowerCase();
            if (n.equals(e)) return especialidad;
            if (n.contains(e)) return especialidad;
            if (e.contains(n) && n.length() >= 5) mejor = especialidad;
        }
        return mejor;
    }

    private String extraerGrupo(String texto, String regex) {
        if (texto == null) return null;
        Matcher m = Pattern.compile(regex).matcher(texto);
        if (!m.find()) return null;
        String valor = m.group(1).trim();
        return valor.isBlank() ? null : valor;
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
                .subjetivo("Patient refiere: " + (t.length() > 300 ? t.substring(0, 300) + "..." : t))
                .objetivo("Signos vitales pendientes de registrar (modo demo). Piel: cálida y húmeda, mucosas rosadas.")
                .apreciacion("Cuadro probable de origen inespecífico (modo demo), correlacionar con examen físico completo.")
                .plan(MODO_DEMO_SIN_API_KEY +
                        " 1) Toma de constantes completas. 2) Paraclínicos según impresión. 3) Reevaluación en 48h. 4) Firma médica.")
                .diagnosticoPresuntivo("Diagnóstico diferencial pendiente de confirmación")
                .procedimientosSugeridos("-")
                .modoDemo(true).build();
    }

    /* ============ 4. Interacciones Fármaco-Alimento (GNN) ============== */
    public AiInteractionResponse interacciones(AiInteractionRequest req) {
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
    public AiPricesResponse precios(AiPricesRequest req) {
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
            AiPricesResponse r = mapper.readValue(llm.get(), AiPricesResponse.class);
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

        List<AiPricesResponse.ComparativaFarmacia> comp = List.of(
                AiPricesResponse.ComparativaFarmacia.builder()
                        .nombre("Cruz Verde").precio(base * 1.05).disponibilidad("En stock").url("https://www.cruzverde.com.co").build(),
                AiPricesResponse.ComparativaFarmacia.builder()
                        .nombre("Farmatodo").precio(base * 0.98).disponibilidad("En stock").url("https://www.farmatodo.com.co").build(),
                AiPricesResponse.ComparativaFarmacia.builder()
                        .nombre("Droguería Cafam").precio(base * 0.86).disponibilidad("3 disponibles").url("https://www.drogueriascafam.com.co").build(),
                AiPricesResponse.ComparativaFarmacia.builder()
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

        return AiPricesResponse.builder()
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
