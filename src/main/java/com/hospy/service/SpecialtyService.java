package com.hospy.service;

import com.hospy.dto.SpecialtyDto;
import com.hospy.model.Specialty;
import com.hospy.repository.SpecialtyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Specialty catalog used by Registro Hospy and doctor forms. */
@Service
@RequiredArgsConstructor
public class SpecialtyService {

    public static final List<String> DEFAULTS = List.of(
            "Medicina General", "Cardiología", "Neurología", "Dermatología", "Traumatología",
            "Ortopedia", "Pediatría", "Ginecología", "Oftalmología", "Otorrinolaringología",
            "Gastroenterología", "Endocrinología", "Urología", "Psiquiatría", "Neumología",
            "Medicina Interna", "Reumatología", "Oncología", "Nefrología", "Infectología");

    private final SpecialtyRepository repository;

    @Transactional
    public void ensureDefaults() {
        for (String nombre : DEFAULTS) {
            if (findSame(nombre).isEmpty()) {
                repository.save(Specialty.builder().nombre(nombre).build());
            }
        }
    }

    public List<String> names() {
        List<String> guardadas = repository.findAllByOrderByNombreAsc().stream().map(Specialty::getNombre).toList();
        return guardadas.isEmpty() ? DEFAULTS : guardadas;
    }

    public List<SpecialtyDto> list() {
        return repository.findAllByOrderByNombreAsc().stream()
                .map(s -> SpecialtyDto.builder().id(s.getId()).nombre(s.getNombre()).build())
                .toList();
    }

    @Transactional
    public SpecialtyDto create(String raw) {
        String nombre = tidy(raw);
        if (nombre == null) {
            throw new RuntimeException("Escribe el nombre de la especialización.");
        }
        Optional<Specialty> ya = findSame(nombre);
        if (ya.isPresent()) {
            return SpecialtyDto.builder().id(ya.get().getId()).nombre(ya.get().getNombre()).yaExistia(true).build();
        }
        Specialty saved = repository.save(Specialty.builder().nombre(nombre).build());
        return SpecialtyDto.builder().id(saved.getId()).nombre(saved.getNombre()).yaExistia(false).build();
    }

    public String tidy(String raw) {
        if (raw == null) return null;
        String limpio = raw.trim().replaceAll("\\s+", " ");
        limpio = limpio.replaceAll("(?i)^(se llama|llamada|llamado|es|de|del)\\s+", "");
        limpio = limpio.replaceAll("[.。]+$", "").trim();
        if (limpio.length() < 3 || limpio.length() > 60) return null;
        if (!limpio.matches("[\\p{L}][\\p{L} .'-]*")) return null;
        String plano = plain(limpio);
        if (plano.equals("nueva") || plano.equals("nuevo") || plano.equals("especialidad") || plano.equals("especializacion")) {
            return null;
        }
        String[] partes = limpio.split(" ");
        StringBuilder titulo = new StringBuilder();
        for (int i = 0; i < partes.length; i++) {
            String p = partes[i];
            String menor = p.toLowerCase(Locale.ROOT);
            boolean union = i > 0 && (menor.equals("de") || menor.equals("del") || menor.equals("la")
                    || menor.equals("las") || menor.equals("los") || menor.equals("y") || menor.equals("e"));
            if (titulo.length() > 0) titulo.append(' ');
            if (union) {
                titulo.append(menor);
            } else if (p.length() == 1) {
                titulo.append(p.toUpperCase(Locale.ROOT));
            } else {
                titulo.append(p.substring(0, 1).toUpperCase(Locale.ROOT)).append(p.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return titulo.toString();
    }

    private Optional<Specialty> findSame(String nombre) {
        String clave = plain(nombre);
        return repository.findAll().stream().filter(s -> plain(s.getNombre()).equals(clave)).findFirst();
    }

    private static String plain(String valor) {
        return Normalizer.normalize(valor, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .trim();
    }
}
