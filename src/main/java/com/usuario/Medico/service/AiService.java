package com.usuario.Medico.service;

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
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class AiService {

    public static final String MODO_DEMO_SIN_API_KEY =
            "MODO DEMO: Este resultado es una simulación educativa. " +
                "Configura OPENAI_API_KEY para solicitar una respuesta al proveedor de lenguaje configurado.";

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
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public boolean hayApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public AiTranscripcionResponse transcribir(byte[] audio, String contentType) {
        if (!hayApiKey()) {
            return new AiTranscripcionResponse("", true, "Configura OPENAI_API_KEY para transcribir el audio con Whisper.");
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

    /* ============ Llamada genérica al LLM ============== */
    private Optional<String> llamarLlm(String systemPrompt, String userPrompt) {
        if (!hayApiKey()) return Optional.empty();
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
                log.warn("Llamada a IA fallo status={}, body={}", resp.statusCode(), resp.body());
                return Optional.empty();
            }
            JsonNode root = mapper.readTree(resp.body());
            String content = root.path("choices").get(0).path("message").path("content").asText();
            return Optional.of(content);
        } catch (Exception ex) {
            log.warn("Excepcion en llamada IA: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /* ============ 1. Triage / NLP sintomas -> ESI/MTS ============== */
    public AiTriageResponse triage(AiTriageRequest req) {
        final String system = """
                Eres un asistente de lenguaje que organiza información sobre síntomas para revisión profesional.
                No eres un servicio de urgencias ni un sistema clínico validado. No afirmes que aplicas oficialmente ESI/MTS.
                Si no hay información suficiente, indica incertidumbre y recomienda valoración profesional.
                Devuelve UNICAMENTE JSON con esta estructura (sin explicaciones):
                {"severidad":"Rojo|Naranja|Amarillo|Verde|Azul","escala":"ESI/MTS 5 niveles","nivelEsi":1..5,
                 "especialidadRecomendada":"...","prioridad":1..10,
                 "hallazgos":["h1","h2"],"recomendaciones":"...","resumen":"2-3 oraciones"}
                """;
        final String user = """
                Analiza estos síntomas e infiere triaje clínico:
                - Síntomas: %s
                - Duración: %s
                - Antecedentes: %s
                - Edad paciente: %s
                """.formatted(req.getSintomas(), nvl(req.getDuracion()), nvl(req.getAntecedentes()),
                req.getEdadPaciente() == null ? "no informado" : req.getEdadPaciente().toString());

        Optional<String> llm = llamarLlm(system, user);
        if (llm.isEmpty()) return datasets.triage(req);
        try {
            AiTriageResponse r = mapper.readValue(llm.get(), AiTriageResponse.class);
            r.setModoDemo(false);
            return r;
        } catch (Exception ex) {
            return triageMock(req);
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

    /* ============ 2. Dermatología (CNN simulado) ============== */
    public AiDermatologiaResponse dermatologia(AiDermatologiaRequest req) {
        final String system = """
                Eres un asistente de lenguaje que organiza una descripción textual de una lesión para revisión profesional.
                No procesas imágenes, no eres una CNN y no debes afirmar características visuales no descritas por el usuario.
                No diagnostiques; destaca la incertidumbre y recomienda revisión clínica.
                Responde SOLO JSON: {"nivelRiesgo":"BAJO|MEDIO|ALTO|CRITICO","scoreRiesgo":0.0..1.0,
                 "diagnosticosDiferenciales":["d1","d2"],"caracteristicasObservadas":["c1","c2"],
                 "recomendaciones":"...","advertencia":"no sustituye dermatólogo"}
                """;
        String user = "Descripción paciente: " + req.getDescripcion() +
                " | Evolución: " + nvl(req.getTiempoEvolucion()) +
                " | Síntomas asociados: " + nvl(req.getSintomasAsociados()) +
                " | ImagenBase64 presente?: " + (req.getImagenBase64() != null && !req.getImagenBase64().isBlank());

        Optional<String> llm = llamarLlm(system, user);
        if (llm.isEmpty()) return datasets.dermatologia(req);
        try {
            AiDermatologiaResponse r = mapper.readValue(llm.get(), AiDermatologiaResponse.class);
            r.setModoDemo(false);
            return r;
        } catch (Exception ex) {
            return dermatologiaMock(req);
        }
    }

    private AiDermatologiaResponse dermatologiaMock(AiDermatologiaRequest req) {
        String desc = req.getDescripcion().toLowerCase();
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
}
