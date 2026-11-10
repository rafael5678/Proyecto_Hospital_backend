package com.hospy.service;

import com.hospy.dto.AiSoapRequest;
import com.hospy.dto.AiSoapResponse;

/**
 * Doctor-facing SOAP copy from the visit file. Screen text stays in Spanish.
 * Used when Claude is slow and to replace a summary that does not name the case.
 */
final class SoapBrief {

    private SoapBrief() {
    }

    static AiSoapResponse from(AiSoapRequest req) {
        Case c = parse(req);
        return AiSoapResponse.builder()
                .resumenParaMedico(summary(c))
                .enfoqueEnConsulta(exam(c))
                .preguntasSugeridas(questions(c))
                .medicamentosSugeridos(meds(c))
                .cuidados(care(c))
                .subjetivo(c.story.isBlank() ? "No informado" : c.story)
                .objetivo(exam(c))
                .apreciacion(impression(c))
                .plan("1) Confirmar el relato. 2) Examinar. 3) Elegir analgésico solo si no choca con alergias. 4) Dejar cuidados por escrito.")
                .diagnosticoPresuntivo(impression(c))
                .procedimientosSugeridos(procedures(c))
                .modoDemo(true)
                .build();
    }

    static AiSoapResponse complete(AiSoapResponse r, AiSoapRequest req) {
        AiSoapResponse local = from(req);
        if (r == null) return local;
        if (thin(r.getResumenParaMedico())) r.setResumenParaMedico(local.getResumenParaMedico());
        if (blank(r.getEnfoqueEnConsulta()) || genericExam(r.getEnfoqueEnConsulta())) {
            r.setEnfoqueEnConsulta(local.getEnfoqueEnConsulta());
        }
        if (blank(r.getCuidados())) r.setCuidados(local.getCuidados());
        if (blank(r.getMedicamentosSugeridos())) r.setMedicamentosSugeridos(local.getMedicamentosSugeridos());
        if (blank(r.getPreguntasSugeridas())) r.setPreguntasSugeridas(local.getPreguntasSugeridas());
        if (blank(r.getSubjetivo()) || "no informado".equalsIgnoreCase(r.getSubjetivo().trim())) {
            r.setSubjetivo(local.getSubjetivo());
        }
        r.setModoDemo(r.getModoDemo() != null && r.getModoDemo());
        return r;
    }

    static boolean thin(String resumen) {
        if (blank(resumen)) return true;
        String t = resumen.toLowerCase();
        return t.contains("aún hay que caracterizar")
                || t.contains("aun hay que caracterizar")
                || t.contains("no cierres el diagnóstico solo con el relato")
                || (t.length() < 90 && t.contains("malestar"));
    }

    private static boolean genericExam(String s) {
        String t = s.toLowerCase();
        return t.contains("inspecciona y palpa la zona, compara con el lado sano")
                && !t.contains("moret") && !t.contains("barriga") && !t.contains("golpe");
    }

    private static String summary(Case c) {
        StringBuilder sb = new StringBuilder();
        sb.append("Trae ").append(c.complaint);
        if (!c.duration.isBlank()) sb.append(" desde hace ").append(c.duration);
        sb.append(". ");
        if (c.trauma) {
            sb.append("Relato de golpe con moretón y a veces calambre: encaja con contusión o hematoma de partes blandas; hay que mirar fuerza, movilidad y si el moretón cambia. ");
        } else if (c.digestive) {
            sb.append("Cuadro digestivo (barriga, náusea o lo que comió). Pensar gastritis, indigestión o gastroenteritis leve. ");
        } else if (c.skin) {
            sb.append("Hay un cambio de piel o lesión; correlacionar con la foto y el relato, no con un rótulo genérico. ");
        } else if (c.respiratory) {
            sb.append("Cuadro respiratorio leve a orientar (tos, resfriado o garganta). ");
        } else if (c.head) {
            sb.append("Cefalea o mareo a caracterizar en el examen (tensión, migraña, deshidratación). ");
        } else if (!c.story.isBlank()) {
            sb.append("Según el relato: ").append(clip(c.story, 220)).append(". ");
        }
        if (!c.allergies.isBlank()) {
            sb.append("Alergias: ").append(c.allergies).append(". No medicar con lo que le da reacción. ");
        }
        sb.append("Recomendación: examina ahora, ").append(c.trauma
                ? "hielo corto y reposo relativo, analgésico permitido, y Rx solo si hay deformidad o no apoya."
                : "cuidados concretos y un analgésico solo si el examen lo permite y no choca con alergias.");
        return sb.toString();
    }

    private static String exam(Case c) {
        if (c.trauma) {
            return "Inspecciona el moretón, compara con el otro brazo, palpa, mira movilidad, fuerza y pulsos. Descarta palidez, frialdad, hormigueo o que no mueva los dedos.";
        }
        if (c.digestive) {
            return "Abdomen: inspección, ruidos, dolor a la palpación, defensa. Pregunta fiebre, vómito, sangre o si no tolera líquidos.";
        }
        if (c.skin) {
            return "Mira la foto y la zona: color, borde, calor, dolor, extensión. Correlaciónala con el golpe o el síntoma, no la leas sola.";
        }
        return "Examina la zona que duele, compara con el lado sano y busca signos de alarma (fiebre, déficit, dolor que no deja apoyar).";
    }

    private static String questions(Case c) {
        if (c.trauma) {
            return "¿Cómo fue el golpe y pudo apoyar después?\n¿El moretón creció o cambió de color?\n¿El calambre es de la mano o sube?\n¿Hay hormigueo, palidez o pierde fuerza?\n¿Ya tomó algo y qué (por las alergias)?\n¿Le había pasado antes en ese brazo?";
        }
        if (c.digestive) {
            return "¿Qué comió antes y hubo vómito o diarrea?\n¿El dolor es en puntada o ardor?\n¿Hay fiebre o no tolera líquidos?\n¿Heces o vómito con sangre?\n¿Ya tomó algo y es alérgico a dipirona u otro?\n¿Le había pasado?";
        }
        return "¿Desde cuándo le pasa y cómo empezó?\n¿Qué lo empeora y qué lo calma?\n¿Ya tomó algo, qué dosis y le sirvió?\n¿Hay fiebre, hinchazón o déficit?\n¿Alergias a fármacos?\n¿Le había pasado antes?";
    }

    private static String meds(Case c) {
        boolean noDipirona = contains(c.allergies, "dipirona", "metamizol");
        boolean noAine = contains(c.allergies, "aine", "ibuprofeno", "nsaid");
        String pain = noDipirona && noAine
                ? "Paracetamol · tabletas 500 mg · dolor, si no hay otra alergia"
                : (noDipirona
                ? "Paracetamol · tabletas 500 mg · dolor (evitar dipirona/metamizol)"
                : "Paracetamol · tabletas 500 mg · dolor e inflamación leve, si no hay alergia");
        String rest = c.trauma
                ? "Medida no farmacológica · hielo 10–15 min y reposo relativo · hematoma/contusión"
                : "Medida no farmacológica · reposo relativo y movimiento suave · mientras se aclara";
        return pain + "\n" + rest;
    }

    private static String care(Case c) {
        String extra = c.allergies.isBlank() ? "" : " Respeta las alergias (" + c.allergies + ").";
        if (c.trauma) {
            return "Reposo relativo del brazo, frío local corto si no hay herida, no aprietes el moretón."
                    + extra
                    + " Vuelve antes si se pone pálido, frío, se calambra más o pierde fuerza.";
        }
        if (c.digestive) {
            return "Comida liviana, agua de a sorbos, sin fritura ni alcohol." + extra
                    + " Urgencias si el dolor se clava, hay fiebre o vómito con sangre.";
        }
        return "Reposo relativo, hidratación y volver si aparece fiebre o el dolor se pone intenso." + extra;
    }

    private static String impression(Case c) {
        if (c.trauma) return "Contusión / hematoma de partes blandas a confirmar en el examen";
        if (c.digestive) return "Síntoma digestivo a estudio (gastritis o indigestión)";
        if (c.skin) return "Lesión cutánea a correlacionar con el relato";
        if (c.respiratory) return "Cuadro respiratorio leve a estudio";
        if (c.head) return "Cefalea a estudio";
        return "Motivo de consulta a confirmar en el examen";
    }

    private static String procedures(Case c) {
        if (c.trauma) return "Examen físico dirigido. Radiografía si hay deformidad, no apoya o no mejora.";
        if (c.skin) return "Inspección de la zona y de la foto. No biopsia en esta cita salvo alarma.";
        return "Examen físico dirigido. Imagen solo si hay trauma, déficit o no mejora.";
    }

    private static Case parse(AiSoapRequest req) {
        String motivo = value(req.getMotivoInicial());
        String tx = value(req.getTranscripcionConsulta());
        String all = (motivo + "\n" + tx).toLowerCase();
        Case c = new Case();
        c.complaint = motivo.isBlank() ? firstLine(tx, "este malestar") : clip(motivo, 160);
        c.story = story(tx, motivo);
        c.duration = field(tx, "(?i)desde hace\\s*[:]?\\s*([^\\n]+)") ;
        if (c.duration.isBlank()) {
            var m = java.util.regex.Pattern.compile("(\\d+)\\s*d[ií]as").matcher(all);
            if (m.find()) c.duration = m.group(1) + " días";
        }
        c.allergies = field(tx, "(?i)(?:antecedentes|alergias)\\s*[:]?\\s*([^\\n]+)");
        c.trauma = contains(all, "golpe", "moret", "hematoma", "contusi", "calambr", "esguince", "brazo");
        c.digestive = contains(all, "barriga", "estomago", "estómago", "abdomen", "vomit", "diarrea", "gastr");
        c.skin = contains(all, "piel", "mancha", "roncha", "lesión", "lesion cut", "foto");
        c.respiratory = contains(all, "tos", "gripe", "resfriado", "garganta");
        c.head = contains(all, "cabeza", "cefalea", "mareo");
        return c;
    }

    private static String story(String tx, String motivo) {
        String s = field(tx, "(?i)s[ií]ntomas(?: que refiri[oó])?\\s*[:]?\\s*([^\\n]+)");
        if (s.isBlank()) s = motivo;
        if (s.isBlank()) s = clip(tx.replaceAll("(?i)---.*?---", " ").replace('\n', ' '), 280);
        return s.trim();
    }

    private static String field(String tx, String regex) {
        if (tx == null) return "";
        var m = java.util.regex.Pattern.compile(regex).matcher(tx);
        return m.find() ? m.group(1).trim() : "";
    }

    private static String firstLine(String tx, String fallback) {
        if (tx == null || tx.isBlank()) return fallback;
        for (String line : tx.split("\n")) {
            String t = line.trim();
            if (t.isBlank() || t.startsWith("---") || t.toLowerCase().startsWith("antecedentes")) continue;
            return clip(t.replaceFirst("(?i)^s[ií]ntomas[^:]*:\\s*", ""), 160);
        }
        return fallback;
    }

    private static String clip(String s, int n) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return t.length() <= n ? t : t.substring(0, n).trim() + "…";
    }

    private static String value(String s) {
        return s == null || s.isBlank() || "no informado".equalsIgnoreCase(s.trim()) ? "" : s.trim();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean contains(String texto, String... keys) {
        if (texto == null || texto.isBlank()) return false;
        for (String k : keys) {
            if (k != null && texto.contains(k.toLowerCase())) return true;
        }
        return false;
    }

    private static final class Case {
        String complaint = "este malestar";
        String story = "";
        String duration = "";
        String allergies = "";
        boolean trauma;
        boolean digestive;
        boolean skin;
        boolean respiratory;
        boolean head;
    }
}
