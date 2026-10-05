package com.usuario.Medico.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.usuario.Medico.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
public class ClinicalDatasetService {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SkinImageClassifier skinImageClassifier;

    public ClinicalDatasetService(SkinImageClassifier skinImageClassifier) {
        this.skinImageClassifier = skinImageClassifier;
    }

    public AiTriageResponse triage(AiTriageRequest req) {
        String texto = (nvl(req.getSintomas()) + " " + nvl(req.getDuracion()) + " " + nvl(req.getAntecedentes()))
                .toLowerCase(Locale.ROOT);
        JsonNode filas = leer("datasets/triage-esi-mts.json");
        JsonNode extra = leer("datasets/triage-medqa-medmcqa.json");
        String sev = "Verde";
        int esi = 4;
        int prio = 4;
        String esp = "Medicina General";
        List<String> hallazgos = new ArrayList<>();
        hallazgos.add("ESI/MTS local + muestra MedMCQA/MedQA (Hugging Face, procesada).");
        boolean hit = false;
        if (filas != null) {
            for (JsonNode fila : filas) {
                if (contieneAlguna(texto, fila.get("claves"))) {
                    sev = fila.path("severidad").asText(sev);
                    esi = fila.path("esi").asInt(esi);
                    prio = fila.path("prioridad").asInt(prio);
                    esp = fila.path("especialidad").asText(esp);
                    hallazgos.add(fila.path("hallazgo").asText());
                    hit = true;
                    break;
                }
            }
        }
        if (!hit && extra != null) {
            for (JsonNode fila : extra) {
                if (contieneAlguna(texto, fila.get("claves"))) {
                    sev = fila.path("severidad").asText(sev);
                    esi = fila.path("esi").asInt(esi);
                    prio = fila.path("prioridad").asInt(prio);
                    esp = fila.path("especialidad").asText(esp);
                    hallazgos.add(fila.path("hallazgo").asText());
                    break;
                }
            }
        }
        return AiTriageResponse.builder()
                .severidad(sev).escala("ESI/MTS + MedMCQA/MedQA").nivelEsi(esi)
                .especialidadRecomendada(esp).prioridad(prio).hallazgos(hallazgos)
                .recomendaciones("Orientación automática con dataset ESI/MTS. No sustituye urgencias ni consulta médica.")
                .resumen("Severidad " + sev + " (prioridad " + prio + "/10) hacia " + esp + ".")
                .modoDemo(false)
                .build();
    }

    public AiDermatologiaResponse dermatologia(AiDermatologiaRequest req) {
        AiDermatologiaResponse porImagen = skinImageClassifier.clasificar(req.getImagenBase64());
        String texto = (nvl(req.getDescripcion()) + " " + nvl(req.getSintomasAsociados())
                + " " + nvl(req.getTiempoEvolucion())).toLowerCase(Locale.ROOT);
        JsonNode filas = leer("datasets/dermatologia-ham10000.json");
        String riesgo = "BAJO";
        double score = 0.2;
        List<String> dx = new ArrayList<>(List.of("Dermatitis inespecífica"));
        List<String> car = new ArrayList<>();
        boolean hitTexto = false;
        if (filas != null && !texto.isBlank()) {
            for (JsonNode fila : filas) {
                if (contieneAlguna(texto, fila.get("claves"))) {
                    riesgo = fila.path("riesgo").asText(riesgo);
                    score = fila.path("score").asDouble(score);
                    dx = new ArrayList<>(textos(fila.get("dx")));
                    car.add(fila.path("car").asText());
                    hitTexto = true;
                    break;
                }
            }
        }
        if (porImagen != null) {
            List<String> mergedDx = new ArrayList<>(nvlList(porImagen.getDiagnosticosDiferenciales()));
            if (hitTexto) {
                for (String d : dx) {
                    if (mergedDx.stream().noneMatch(x -> x.equalsIgnoreCase(d))) mergedDx.add(d);
                }
            }
            List<String> mergedCar = new ArrayList<>(nvlList(porImagen.getCaracteristicasObservadas()));
            mergedCar.addAll(car);
            if (!nvl(req.getDescripcion()).isBlank()) {
                mergedCar.add("También se tuvo en cuenta la descripción escrita del paciente.");
            }
            String nivel = peorRiesgo(porImagen.getNivelRiesgo(), riesgo);
            double sc = Math.max(nvl(porImagen.getScoreRiesgo()), score);
            return AiDermatologiaResponse.builder()
                    .nivelRiesgo(nivel)
                    .scoreRiesgo(sc)
                    .diagnosticosDiferenciales(mergedDx)
                    .caracteristicasObservadas(mergedCar)
                    .recomendaciones(porImagen.getRecomendaciones())
                    .advertencia(porImagen.getAdvertencia())
                    .modoDemo(false)
                    .build();
        }
        if (!car.isEmpty() || hitTexto) {
            car.add(0, "Clasificación textual con etiquetas tipo HAM10000 / PAD-UFES (sin foto usable).");
        } else {
            car.add("Sin foto y sin coincidencia textual clara. Se sugiere Dermatología para inspección presencial.");
        }
        return AiDermatologiaResponse.builder()
                .nivelRiesgo(riesgo).scoreRiesgo(score)
                .diagnosticosDiferenciales(dx).caracteristicasObservadas(car)
                .recomendaciones("Correlacionar con examen físico. Dataset de lesiones pigmentadas de referencia educativa.")
                .advertencia("No sustituye consulta con dermatólogo.")
                .modoDemo(false).build();
    }

    private static String peorRiesgo(String a, String b) {
        return rango(a) >= rango(b) ? nvl(a, "BAJO") : nvl(b, "BAJO");
    }

    private static int rango(String r) {
        return switch (nvl(r).toUpperCase(Locale.ROOT)) {
            case "CRITICO" -> 4;
            case "ALTO" -> 3;
            case "MEDIO" -> 2;
            default -> 1;
        };
    }

    private static List<String> nvlList(List<String> in) {
        return in == null ? new ArrayList<>() : in;
    }

    private static Double nvl(Double v) {
        return v == null ? 0.0 : v;
    }

    private static String nvl(String s, String def) {
        return s == null || s.isBlank() ? def : s;
    }

    public AiInteraccionResponse interacciones(AiInteraccionRequest req) {
        List<String> meds = minusculas(req.getMedicamentos());
        List<String> dieta = minusculas(req.getDietaHabitual());
        JsonNode filas = leer("datasets/interacciones-drugbank.json");
        List<AiInteraccionResponse.AlertaInteraccion> alertas = new ArrayList<>();
        String riesgo = "BAJO";
        if (filas != null) {
            for (JsonNode fila : filas) {
                if (contieneLista(meds, fila.get("med")) && contieneLista(dieta, fila.get("dieta"))) {
                    riesgo = "ALTO".equals(fila.path("severidad").asText()) || "GRAVE".equals(fila.path("severidad").asText())
                            ? "ALTO" : "MEDIO";
                    alertas.add(AiInteraccionResponse.AlertaInteraccion.builder()
                            .elementos(fila.path("elementos").asText())
                            .tipo(fila.path("tipo").asText())
                            .severidad(fila.path("severidad").asText())
                            .mecanismo(fila.path("mecanismo").asText())
                            .consecuencia(fila.path("consecuencia").asText())
                            .accion(fila.path("accion").asText())
                            .build());
                }
            }
        }
        if (alertas.isEmpty()) {
            alertas.add(AiInteraccionResponse.AlertaInteraccion.builder()
                    .elementos("Revisión dataset DrugBank/FOODB")
                    .tipo("GENERAL").severidad("INFORMATIVA")
                    .mecanismo("No hubo coincidencia exacta en el subconjunto local")
                    .consecuencia("No confirma ausencia de interacciones")
                    .accion("Confirmar con médico o farmacéutico").build());
        }
        return AiInteraccionResponse.builder()
                .nivelRiesgoGlobal(riesgo).alertas(alertas)
                .recomendacionesDieteticas(List.of("Tomar medicamentos con agua", "No suspender tratamiento por esta orientación"))
                .resumen("Análisis con dataset local de interacciones fármaco-alimento.")
                .modoDemo(false).build();
    }

    private JsonNode leer(String ruta) {
        try (InputStream in = new ClassPathResource(ruta).getInputStream()) {
            return mapper.readTree(in);
        } catch (Exception ex) {
            log.warn("No se pudo leer {}: {}", ruta, ex.getMessage());
            return null;
        }
    }

    private static boolean contieneAlguna(String texto, JsonNode claves) {
        if (claves == null || !claves.isArray()) return false;
        for (JsonNode c : claves) {
            if (texto.contains(c.asText().toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static boolean contieneLista(List<String> valores, JsonNode claves) {
        if (valores == null || claves == null) return false;
        String joined = String.join(" ", valores);
        return contieneAlguna(joined, claves);
    }

    private static List<String> textos(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) arr.forEach(n -> out.add(n.asText()));
        return out.isEmpty() ? List.of("Sin etiqueta") : out;
    }

    private static List<String> minusculas(List<String> in) {
        if (in == null) return List.of();
        return in.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
    }

    private static String nvl(String s) { return s == null ? "" : s; }
}

// Servicio de Datos Clinicos: Carga de datasets locales ESI y catalogos clinicos.
