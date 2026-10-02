package com.usuario.Medico.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.usuario.Medico.dto.AiDermatologiaResponse;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
public class SkinImageClassifier {

    private static final int SIZE = 64;
    private static final int BINS = 16;
    private static final int GRID = 8;

    private final ObjectMapper mapper = new ObjectMapper();
    private boolean listo;
    private List<String> classes = List.of();
    private double[] mean = new double[0];
    private double[] scale = new double[0];
    private double[][] coef = new double[0][0];
    private double[] intercept = new double[0];
    private List<Tree> trees = List.of();
    private boolean forest;
    private JsonNode labelsEs;
    private JsonNode riesgo;
    private double accuracyTest;

    private record Tree(int[] left, int[] right, int[] feat, double[] thr, double[][] value) {}

    @PostConstruct
    void cargar() {
        try (InputStream in = new ClassPathResource("datasets/skin-classifier.json").getInputStream()) {
            JsonNode root = mapper.readTree(in);
            classes = new ArrayList<>();
            root.path("classes").forEach(n -> classes.add(n.asText()));
            mean = toArray(root.get("mean"));
            scale = toArray(root.get("scale"));
            labelsEs = root.get("labelsEs");
            riesgo = root.get("riesgo");
            accuracyTest = root.path("accuracyTest").asDouble(0);
            forest = "forest".equalsIgnoreCase(root.path("type").asText());
            if (forest) {
                trees = new ArrayList<>();
                for (JsonNode t : root.path("trees")) {
                    trees.add(new Tree(
                            toIntArray(t.get("left")),
                            toIntArray(t.get("right")),
                            toIntArray(t.get("feat")),
                            toArray(t.get("thr")),
                            toMatrix(t.get("value"))
                    ));
                }
                listo = !classes.isEmpty() && !trees.isEmpty();
            } else {
                intercept = toArray(root.get("intercept"));
                JsonNode coefNode = root.get("coef");
                coef = new double[coefNode.size()][];
                for (int i = 0; i < coefNode.size(); i++) coef[i] = toArray(coefNode.get(i));
                listo = !classes.isEmpty() && coef.length == classes.size();
            }
            log.info("Clasificador de piel cargado: tipo={} clases={} acc_test={}",
                    forest ? "bosque" : "lineal", classes.size(), accuracyTest);
        } catch (Exception ex) {
            listo = false;
            log.warn("Sin modelo de piel entrenado ({}). Se usará solo texto.", ex.getMessage());
        }
    }

    public boolean disponible() {
        return listo;
    }

    public AiDermatologiaResponse clasificar(String imagenBase64) {
        if (!listo || imagenBase64 == null || imagenBase64.isBlank()) return null;
        try {
            double[] feats = extraer(decodificar(imagenBase64));
            double[] probs = forest ? inferirBosque(feats) : inferirLineal(feats);
            int top = argmax(probs);
            int second = argmaxExcept(probs, top);
            String clase = classes.get(top);
            String dx = etiqueta(clase);
            String nivel = riesgoDe(clase);
            double score = scoreDe(nivel, probs[top]);
            List<String> diferenciales = new ArrayList<>();
            diferenciales.add(dx + " (" + pct(probs[top]) + ")");
            if (second >= 0) diferenciales.add(etiqueta(classes.get(second)) + " (" + pct(probs[second]) + ")");
            diferenciales.add("Otras dermatosis a correlacionar en consulta");
            List<String> car = new ArrayList<>();
            car.add("Modelo local entrenado con el dataset de 8 enfermedades de piel (bosque + color/textura).");
            car.add("Precisión de prueba del modelo: " + pct(accuracyTest) + ".");
            car.add("Confianza de la clase principal: " + pct(probs[top])
                    + (probs[top] < 0.40 ? " (baja; priorizar examen presencial)" : "") + ".");
            car.add("Especialidad sugerida: Dermatología.");
            return AiDermatologiaResponse.builder()
                    .nivelRiesgo(nivel)
                    .scoreRiesgo(score)
                    .diagnosticosDiferenciales(diferenciales)
                    .caracteristicasObservadas(car)
                    .recomendaciones(recomendacion(nivel, dx))
                    .advertencia("Orientación automática sobre foto. No es diagnóstico ni sustituye a un dermatólogo.")
                    .modoDemo(false)
                    .build();
        } catch (Exception ex) {
            log.warn("No se pudo clasificar la imagen de piel: {}", ex.getMessage());
            return null;
        }
    }

    private double[] inferirLineal(double[] feats) {
        double[] logits = new double[classes.size()];
        for (int c = 0; c < classes.size(); c++) {
            double sum = intercept[c];
            for (int i = 0; i < feats.length; i++) sum += coef[c][i] * feats[i];
            logits[c] = sum;
        }
        return softmax(logits);
    }

    private double[] inferirBosque(double[] feats) {
        double[] acc = new double[classes.size()];
        for (Tree t : trees) {
            int node = 0;
            while (t.left[node] != -1) {
                int f = t.feat[node];
                if (f >= 0 && f < feats.length && feats[f] <= t.thr[node]) node = t.left[node];
                else node = t.right[node];
            }
            double[] leaf = t.value[node];
            double sum = 0;
            for (double v : leaf) sum += v;
            if (sum <= 0) continue;
            for (int c = 0; c < acc.length && c < leaf.length; c++) acc[c] += leaf[c] / sum;
        }
        double n = Math.max(1, trees.size());
        for (int i = 0; i < acc.length; i++) acc[i] /= n;
        return acc;
    }

    private BufferedImage decodificar(String raw) throws Exception {
        String data = raw.trim();
        int comma = data.indexOf(',');
        if (data.startsWith("data:") && comma > 0) data = data.substring(comma + 1);
        byte[] bytes = Base64.getDecoder().decode(data.replaceAll("\\s", ""));
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(bytes));
        if (src == null) throw new IllegalArgumentException("formato de imagen no soportado");
        BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, SIZE, SIZE, null);
        g.dispose();
        return out;
    }

    private double[] extraer(BufferedImage img) {
        int nPix = SIZE * SIZE;
        int[] rgbCounts = new int[3 * BINS];
        int[] hsvCounts = new int[3 * BINS];
        double[] gray = new double[nPix];
        double sumR = 0, sumG = 0, sumB = 0;
        double sumR2 = 0, sumG2 = 0, sumB2 = 0;
        int p = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                rgbCounts[bin(r)]++;
                rgbCounts[BINS + bin(g)]++;
                rgbCounts[2 * BINS + bin(b)]++;
                double[] hsv = rgbToHsv(r, g, b);
                hsvCounts[bin((int) Math.round(hsv[0]))]++;
                hsvCounts[BINS + bin((int) Math.round(hsv[1]))]++;
                hsvCounts[2 * BINS + bin((int) Math.round(hsv[2]))]++;
                gray[p++] = (r + g + b) / 3.0;
                sumR += r; sumG += g; sumB += b;
                sumR2 += r * r; sumG2 += g * g; sumB2 += b * b;
            }
        }
        double binWidth = 256.0 / BINS;
        double[] raw = new double[3 * BINS + 3 * BINS + GRID * GRID + 6];
        int idx = 0;
        for (int i = 0; i < rgbCounts.length; i++) raw[idx++] = rgbCounts[i] / (nPix * binWidth);
        for (int i = 0; i < hsvCounts.length; i++) raw[idx++] = hsvCounts[i] / (nPix * binWidth);
        int cell = SIZE / GRID;
        for (int i = 0; i < GRID; i++) {
            for (int j = 0; j < GRID; j++) {
                double s = 0;
                for (int yy = 0; yy < cell; yy++) {
                    for (int xx = 0; xx < cell; xx++) {
                        s += gray[(i * cell + yy) * SIZE + (j * cell + xx)];
                    }
                }
                raw[idx++] = (s / (cell * cell)) / 255.0;
            }
        }
        double meanR = sumR / nPix, meanG = sumG / nPix, meanB = sumB / nPix;
        raw[idx++] = meanR / 255.0;
        raw[idx++] = meanG / 255.0;
        raw[idx++] = meanB / 255.0;
        raw[idx++] = Math.sqrt(Math.max(0, sumR2 / nPix - meanR * meanR)) / 255.0;
        raw[idx++] = Math.sqrt(Math.max(0, sumG2 / nPix - meanG * meanG)) / 255.0;
        raw[idx++] = Math.sqrt(Math.max(0, sumB2 / nPix - meanB * meanB)) / 255.0;

        double[] z = new double[raw.length];
        for (int i = 0; i < raw.length; i++) {
            double s = (i < scale.length && scale[i] != 0) ? scale[i] : 1.0;
            double m = i < mean.length ? mean[i] : 0.0;
            z[i] = (raw[i] - m) / s;
        }
        return z;
    }

    /** HSV 0-255, misma fórmula que spark/train_skin_classifier.py */
    private static double[] rgbToHsv(int r, int g, int b) {
        double rf = r / 255.0, gf = g / 255.0, bf = b / 255.0;
        double mx = Math.max(rf, Math.max(gf, bf));
        double mn = Math.min(rf, Math.min(gf, bf));
        double d = mx - mn;
        double h = 0;
        if (d > 1e-8) {
            if (mx == rf) h = ((gf - bf) / d) % 6.0;
            else if (mx == gf) h = (bf - rf) / d + 2.0;
            else h = (rf - gf) / d + 4.0;
            if (h < 0) h += 6.0;
            h = (h / 6.0) % 1.0;
        }
        double s = mx <= 1e-8 ? 0 : d / mx;
        return new double[]{h * 255.0, s * 255.0, mx * 255.0};
    }

    private static int bin(int value) {
        int v = Math.min(255, Math.max(0, value));
        return Math.min(BINS - 1, v * BINS / 256);
    }

    private String etiqueta(String raw) {
        if (labelsEs != null && labelsEs.has(raw)) return labelsEs.get(raw).asText();
        return raw.replace("BA-", "Bacteriana ")
                .replace("FU-", "Fúngica ")
                .replace("VI-", "Viral ")
                .replace("PA-", "Parasitaria ")
                .replace('-', ' ')
                .trim();
    }

    private String riesgoDe(String clase) {
        if (riesgo != null && riesgo.has(clase)) return riesgo.get(clase).asText().toUpperCase(Locale.ROOT);
        String n = clase.toLowerCase(Locale.ROOT);
        if (n.contains("cellulitis") || n.contains("shingles")) return "ALTO";
        if (n.contains("impetigo") || n.contains("chickenpox") || n.contains("ringworm") || n.contains("larva")) {
            return "MEDIO";
        }
        return "BAJO";
    }

    private static double scoreDe(String nivel, double confianza) {
        double base = switch (nivel) {
            case "CRITICO" -> 0.92;
            case "ALTO" -> 0.78;
            case "MEDIO" -> 0.48;
            default -> 0.24;
        };
        return Math.min(0.97, Math.max(0.12, base * (0.55 + 0.45 * confianza)));
    }

    private static String recomendacion(String nivel, String dx) {
        if ("ALTO".equals(nivel) || "CRITICO".equals(nivel)) {
            return "Posible " + dx + " de riesgo alto. Se asigna Dermatología con prioridad. "
                    + "Si hay fiebre, dolor intenso, extensión rápida o malestar general, acude a urgencias.";
        }
        if ("MEDIO".equals(nivel)) {
            return "Posible " + dx + ". Mantén la zona limpia, evita automedicarte con corticoides potentes "
                    + "y consulta Dermatología para confirmar.";
        }
        return "Posible " + dx + " de menor urgencia. Higiene local y valoración dermatológica programada.";
    }

    private static double[] softmax(double[] logits) {
        double max = logits[0];
        for (double v : logits) max = Math.max(max, v);
        double sum = 0;
        double[] out = new double[logits.length];
        for (int i = 0; i < logits.length; i++) {
            out[i] = Math.exp(logits[i] - max);
            sum += out[i];
        }
        for (int i = 0; i < out.length; i++) out[i] /= sum;
        return out;
    }

    private static int argmax(double[] v) {
        int i = 0;
        for (int k = 1; k < v.length; k++) if (v[k] > v[i]) i = k;
        return i;
    }

    private static int argmaxExcept(double[] v, int skip) {
        int i = -1;
        for (int k = 0; k < v.length; k++) {
            if (k == skip) continue;
            if (i < 0 || v[k] > v[i]) i = k;
        }
        return i;
    }

    private static String pct(double p) {
        return Math.round(p * 1000.0) / 10.0 + "%";
    }

    private static double[] toArray(JsonNode node) {
        if (node == null || !node.isArray()) return new double[0];
        double[] out = new double[node.size()];
        for (int i = 0; i < node.size(); i++) out[i] = node.get(i).asDouble();
        return out;
    }

    private static int[] toIntArray(JsonNode node) {
        if (node == null || !node.isArray()) return new int[0];
        int[] out = new int[node.size()];
        for (int i = 0; i < node.size(); i++) out[i] = node.get(i).asInt();
        return out;
    }

    private static double[][] toMatrix(JsonNode node) {
        if (node == null || !node.isArray()) return new double[0][0];
        double[][] out = new double[node.size()][];
        for (int i = 0; i < node.size(); i++) out[i] = toArray(node.get(i));
        return out;
    }
}
