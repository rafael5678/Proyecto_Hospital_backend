package com.hospy.service;

import com.hospy.dto.AiTriageRequest;
import com.hospy.dto.AiTriageResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * Patient-facing triage copy: specialty from the complaint, care until the visit,
 * and allergy warnings. Screen text stays in Spanish.
 */
final class TriageOrientation {

    private TriageOrientation() {
    }

    static AiTriageResponse polish(AiTriageResponse r, AiTriageRequest req) {
        correctSpecialty(r, req);
        r.setHallazgos(withoutJargon(r.getHallazgos()));
        r.setEscala("Orientación clínica");
        if (r.getResumen() != null && (isJargon(r.getResumen()) || cannedSummary(r.getResumen()))) {
            r.setResumen(null);
        }
        if (r.getRecomendaciones() != null && isJargon(r.getRecomendaciones())) {
            r.setRecomendaciones(null);
        }
        if (r.getResumen() == null || r.getResumen().isBlank()) {
            r.setResumen(summary(req, r));
        }
        if (r.getRecomendaciones() == null || r.getRecomendaciones().isBlank() || thinCare(r.getRecomendaciones())) {
            r.setRecomendaciones(careUntilVisit(req, r));
        }
        if (r.getHallazgos() == null || r.getHallazgos().isEmpty()) {
            r.setHallazgos(findings(req, r));
        }
        return r;
    }

    static AiTriageResponse withoutModel(AiTriageResponse local, AiTriageRequest req) {
        normalize(local, local);
        correctSpecialty(local, req);
        local.setModoDemo(true);
        local.setResumen(summary(req, local));
        local.setRecomendaciones(careUntilVisit(req, local));
        local.setHallazgos(findings(req, local));
        return polish(local, req);
    }

    static void completeAfterModel(AiTriageResponse r, AiTriageResponse local, AiTriageRequest req) {
        correctSpecialty(r, req);
        if (thinSummary(r.getResumen(), local == null ? null : local.getResumen())) {
            r.setResumen(summary(req, r));
        }
        if (thinCare(r.getRecomendaciones())) {
            r.setRecomendaciones(careUntilVisit(req, r));
        }
    }

    static void normalize(AiTriageResponse r, AiTriageResponse local) {
        r.setHallazgos(withoutJargon(r.getHallazgos()));
        if (r.getEspecialidadRecomendada() == null || r.getEspecialidadRecomendada().isBlank()) {
            r.setEspecialidadRecomendada(local.getEspecialidadRecomendada());
        }
        if (r.getSeveridad() == null || r.getSeveridad().isBlank()) r.setSeveridad(local.getSeveridad());
        else r.setSeveridad(titleSeverity(r.getSeveridad()));
        if (r.getNivelEsi() == null) r.setNivelEsi(local.getNivelEsi());
        if (r.getPrioridad() == null) r.setPrioridad(local.getPrioridad());
        if (r.getEscala() == null || r.getEscala().isBlank() || isJargon(r.getEscala())) {
            r.setEscala("Orientación clínica");
        }
        if (r.getResumen() == null || r.getResumen().isBlank() || isJargon(r.getResumen())) {
            r.setResumen(local.getResumen());
        }
    }

    static boolean isJargon(String s) {
        if (s == null) return false;
        String t = s.toLowerCase();
        return t.contains("dataset") || t.contains("ham10000") || t.contains("pad-ufes")
                || t.contains("extratrees") || t.contains("medmcqa") || t.contains("medqa")
                || t.contains("hugging") || t.contains("esi/mts") || t.contains("modelo local")
                || t.contains("precisión de prueba") || t.contains("bosque") || t.contains("extra trees")
                || t.contains("etiquetas tipo") || t.contains("clasificador") || t.contains("gemini no");
    }

    static List<String> withoutJargon(List<String> in) {
        List<String> out = new ArrayList<>();
        if (in == null) return out;
        for (String s : in) {
            if (s != null && !s.isBlank() && !isJargon(s)) out.add(s);
        }
        return out;
    }

    private static boolean thinSummary(String resumen, String local) {
        if (resumen == null || resumen.isBlank() || isJargon(resumen) || cannedSummary(resumen)) return true;
        String t = resumen.trim();
        if (local != null && t.equalsIgnoreCase(local.trim())) return true;
        String l = t.toLowerCase();
        return t.length() < 140 && (l.startsWith("severidad ") || l.startsWith("cuentas:"));
    }

    private static boolean cannedSummary(String resumen) {
        String t = resumen.toLowerCase();
        return t.contains("lo adecuado es agendar") || t.startsWith("cuentas:");
    }

    private static boolean thinCare(String rec) {
        if (rec == null || rec.isBlank() || isJargon(rec)) return true;
        String t = rec.trim().toLowerCase();
        return t.equals("si empeora, hay fiebre o el dolor es intenso, consulta de forma presencial.")
                || (t.startsWith("reserva la cita con")
                && t.contains("no te automediques con productos a los que seas alérgico"));
    }

    private static void correctSpecialty(AiTriageResponse r, AiTriageRequest req) {
        String s = value(req == null ? null : req.getSintomas()).toLowerCase();
        if (s.isBlank()) return;
        String esp = r.getEspecialidadRecomendada() == null ? "" : r.getEspecialidadRecomendada().toLowerCase();
        boolean skin = contains(s, "piel", "mancha", "roncha", "granito", "acné", "acne", "erupcion", "erupción",
                "sarpullido", "urticaria", "lesión cut", "lesion cut");
        if (digestive(s) && !skin && (esp.contains("dermat") || esp.isBlank())) {
            r.setEspecialidadRecomendada("Gastroenterología");
            if (r.getSeveridad() == null || r.getSeveridad().isBlank()
                    || "Verde".equalsIgnoreCase(r.getSeveridad()) || "Azul".equalsIgnoreCase(r.getSeveridad())) {
                r.setSeveridad("Amarillo");
            }
            if (r.getNivelEsi() == null || r.getNivelEsi() >= 4) r.setNivelEsi(3);
            if (r.getPrioridad() == null || r.getPrioridad() <= 3) r.setPrioridad(5);
            if (r.getResumen() != null && r.getResumen().toLowerCase().contains("dermatolog")) r.setResumen(null);
            if (r.getRecomendaciones() != null && r.getRecomendaciones().toLowerCase().contains("dermatolog")) {
                r.setRecomendaciones(null);
            }
        }
    }

    private static String summary(AiTriageRequest req, AiTriageResponse r) {
        String sintomas = value(req.getSintomas());
        String duracion = value(req.getDuracion());
        String ant = value(req.getAntecedentes());
        String sev = r.getSeveridad() == null ? "Amarillo" : r.getSeveridad();
        String esp = r.getEspecialidadRecomendada() == null ? "Medicina General" : r.getEspecialidadRecomendada();
        String s = sintomas.toLowerCase();
        StringBuilder sb = new StringBuilder();
        if (digestive(s)) {
            sb.append("Ese dolor de barriga");
            if (!duracion.isBlank()) sb.append(" que llevas ").append(duracion);
            sb.append(" puede ser una indigestión, gastritis o algo que comiste y no te sentó.");
            if (contains(s, "muscul", "cuerpo", "espalda", "lumb")) {
                sb.append(" Los dolores musculares a veces acompañan cuando hay un cuadro viral o el cuerpo está inflamado.");
            }
        } else if (contains(s, "muscul", "cuerpo cortado", "lumbago", "espalda")) {
            sb.append("Ese dolor muscular");
            if (!duracion.isBlank()) sb.append(" de ").append(duracion);
            sb.append(" puede ser una contractura, cansancio o un cuadro viral leve.");
        } else if (contains(s, "cabeza", "cefalea", "mareo")) {
            sb.append("Ese malestar de cabeza");
            if (!duracion.isBlank()) sb.append(" (").append(duracion).append(")");
            sb.append(" conviene valorarlo; a veces es tensión, migraña o falta de hidratación.");
        } else if (contains(s, "tos", "gripe", "resfriado", "garganta")) {
            sb.append("Lo que describes encaja con un cuadro respiratorio leve");
            if (!duracion.isBlank()) sb.append(" de ").append(duracion);
            sb.append(".");
        } else {
            sb.append("Por lo que cuentas");
            if (!sintomas.isBlank()) sb.append(" (").append(sintomas).append(")");
            if (!duracion.isBlank()) sb.append(", ").append(duracion);
            sb.append(", vale la pena orientarlo con un médico.");
        }
        if (!ant.isBlank()) {
            sb.append(" Ten mucho cuidado con tus alergias y antecedentes (").append(ant)
                    .append("): no te automediques ni tomes nada que te hayas prohibido.");
        }
        sb.append(" La orientación es ").append(sev).append(". Agenda ").append(esp)
                .append(" ahora; el médico confirma qué está pasando.");
        return sb.toString();
    }

    private static String careUntilVisit(AiTriageRequest req, AiTriageResponse r) {
        String esp = r.getEspecialidadRecomendada() == null ? "la especialidad sugerida" : r.getEspecialidadRecomendada();
        String s = value(req.getSintomas()).toLowerCase();
        String ant = value(req.getAntecedentes());
        StringBuilder sb = new StringBuilder();
        sb.append("Reserva ").append(esp).append(" sin dejar pasar el cuadro. ");
        if (digestive(s)) {
            sb.append("Hasta la cita, come liviano (caldo, arroz, banano), toma agua de a sorbos y evita frituras, picante y alcohol. ");
        } else {
            sb.append("Hasta ver al médico, descansa, hidrátate y anota cómo evoluciona lo que sientes. ");
        }
        if (!ant.isBlank()) {
            String a = ant.toLowerCase();
            sb.append("Por tus alergias (").append(ant).append(") no te automediques con lo que te da reacción. ");
            if (contains(a, "dipirona", "metamizol")) sb.append("No tomes dipirona ni metamizol. ");
            if (contains(a, "huevo")) sb.append("Evita huevo y lo que lo lleve. ");
            if (contains(a, "látex", "latex")) sb.append("Avisa que eres alérgico al látex. ");
            if (contains(a, "hongo", "moho")) sb.append("Evita alimentos con moho y ambientes húmedos. ");
        } else {
            sb.append("No te automediques. ");
        }
        if (digestive(s)) {
            sb.append("Si el dolor se clava, el abdomen se pone duro, hay fiebre, vómito con sangre o no puedes tomar líquidos, ve a urgencias antes de la cita.");
        } else {
            sb.append("Si el dolor se vuelve mucho más fuerte, hay fiebre o te cuesta respirar, ve a urgencias antes de la cita.");
        }
        return sb.toString();
    }

    private static List<String> findings(AiTriageRequest req, AiTriageResponse r) {
        List<String> h = new ArrayList<>();
        String s = value(req.getSintomas());
        String dur = value(req.getDuracion());
        String ant = value(req.getAntecedentes());
        if (!s.isBlank()) h.add("Lo que sientes: " + s);
        if (!dur.isBlank()) h.add("Desde hace: " + dur);
        if (!ant.isBlank()) h.add("Alergias y antecedentes a cuidar: " + ant);
        if (r.getEspecialidadRecomendada() != null) {
            h.add("Especialidad que encaja con este relato: " + r.getEspecialidadRecomendada());
        }
        return h;
    }

    private static boolean digestive(String s) {
        return contains(s, "barriga", "abdomen", "estomago", "estómago", "panza", "gastr", "indigest",
                "vomit", "vómit", "diarrea", "nause", "náuse", "acidez", "agruras", "colico", "cólico", "empacho");
    }

    private static boolean contains(String texto, String... keys) {
        if (texto == null || texto.isBlank()) return false;
        for (String c : keys) {
            if (c != null && !c.isBlank() && texto.contains(c.toLowerCase())) return true;
        }
        return false;
    }

    private static String value(String s) {
        if (s == null || s.isBlank() || "no informado".equalsIgnoreCase(s.trim())) return "";
        return s.trim();
    }

    private static String titleSeverity(String s) {
        String t = s.trim().toLowerCase();
        if (t.contains("rojo")) return "Rojo";
        if (t.contains("naranja")) return "Naranja";
        if (t.contains("amarillo")) return "Amarillo";
        if (t.contains("azul")) return "Azul";
        if (t.contains("verde")) return "Verde";
        return s;
    }
}
