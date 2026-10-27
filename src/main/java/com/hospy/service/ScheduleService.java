package com.hospy.service;

import com.hospy.dto.ScheduleRequest;
import com.hospy.dto.ScheduleResponse;
import com.hospy.model.Schedule;
import com.hospy.model.Doctor;
import com.hospy.repository.ScheduleRepository;
import com.hospy.repository.DoctorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ScheduleService {

    private final ScheduleRepository horarioRepository;
    private final DoctorRepository medicoRepository;

    public List<ScheduleResponse> listarPorMedico(Long medicoId) {
        Doctor medico = medicoRepository.findById(medicoId)
                .orElseThrow(() -> new RuntimeException("Médico no encontrado"));
        asegurarHorarioLaboral(medico);
        return horarioRepository.findByMedicoAndDisponibleTrue(medico)
                .stream().map(this::toDto).toList();
    }

    public List<ScheduleResponse> misHorarios(String email) {
        Doctor medico = medicoRepository.findByUsuario_Email(email)
                .orElseThrow(() -> new RuntimeException("Médico no encontrado"));
        return horarioRepository.findByMedico(medico).stream().map(this::toDto).toList();
    }

    public ScheduleResponse crear(String emailMedico, ScheduleRequest req) {
        Doctor medico = medicoRepository.findByUsuario_Email(emailMedico)
                .orElseThrow(() -> new RuntimeException("Médico no encontrado"));
        Schedule horario = Schedule.builder()
                .medico(medico)
                .diaSemana(req.getDiaSemana())
                .horaInicio(req.getHoraInicio())
                .horaFin(req.getHoraFin())
                .disponible(req.getDisponible() != null ? req.getDisponible() : true)
                .build();
        return toDto(horarioRepository.save(horario));
    }

    public ScheduleResponse actualizar(Long id, String emailMedico, ScheduleRequest req) {
        Schedule horario = horarioRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Schedule no encontrado"));
        if (!horario.getMedico().getUsuario().getEmail().equals(emailMedico)) {
            throw new RuntimeException("No autorizado");
        }
        horario.setDiaSemana(req.getDiaSemana());
        horario.setHoraInicio(req.getHoraInicio());
        horario.setHoraFin(req.getHoraFin());
        if (req.getDisponible() != null) horario.setDisponible(req.getDisponible());
        return toDto(horarioRepository.save(horario));
    }

    public void eliminar(Long id, String emailMedico) {
        Schedule horario = horarioRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Schedule no encontrado"));
        if (!horario.getMedico().getUsuario().getEmail().equals(emailMedico)) {
            throw new RuntimeException("No autorizado");
        }
        horarioRepository.delete(horario);
    }

    @Transactional
    public void definirHorario(Doctor medico, String dias, String inicio, String fin, String inicio2, String fin2) {
        if (medico == null) return;
        LocalTime h1 = parseHora(inicio);
        LocalTime h2 = parseHora(fin);
        if (h1 == null || h2 == null || !h2.isAfter(h1)) {
            throw new RuntimeException("El horario necesita una hora de inicio y una hora de fin.");
        }
        LocalTime t1 = parseHora(inicio2);
        LocalTime t2 = parseHora(fin2);
        boolean segunda = t1 != null || t2 != null;
        if (segunda && (t1 == null || t2 == null || !t2.isAfter(t1))) {
            throw new RuntimeException("La segunda jornada necesita hora de inicio y hora de fin.");
        }
        List<Integer> lista = new java.util.ArrayList<>();
        if (dias != null) {
            for (String parte : dias.split(",")) {
                String limpio = parte.trim();
                if (limpio.isEmpty()) continue;
                int dia = Integer.parseInt(limpio);
                if (dia >= 1 && dia <= 7 && !lista.contains(dia)) lista.add(dia);
            }
        }
        if (lista.isEmpty()) throw new RuntimeException("Indica los días de atención del médico.");
        horarioRepository.deleteByMedico(medico);
        for (int dia : lista) {
            horarioRepository.save(bloque(medico, dia, h1, h2));
            if (segunda) horarioRepository.save(bloque(medico, dia, t1, t2));
        }
    }

    private Schedule bloque(Doctor medico, int dia, LocalTime inicio, LocalTime fin) {
        return Schedule.builder()
                .medico(medico).diaSemana(dia)
                .horaInicio(inicio).horaFin(fin).disponible(true).build();
    }

    private LocalTime parseHora(String valor) {
        if (valor == null || valor.isBlank()) return null;
        String texto = valor.trim();
        if (texto.matches("\\d{1,2}")) texto = texto + ":00";
        if (texto.matches("\\d{1,2}:\\d{2}")) {
            String[] p = texto.split(":");
            return LocalTime.of(Integer.parseInt(p[0]), Integer.parseInt(p[1]));
        }
        return LocalTime.parse(texto);
    }

    @Transactional
    public void asegurarHorarioLaboral(Doctor medico) {
        if (medico == null || !horarioRepository.findByMedico(medico).isEmpty()) return;
        LocalTime mananaInicio = LocalTime.of(8, 0);
        LocalTime mananaFin = LocalTime.of(12, 0);
        LocalTime tardeInicio = LocalTime.of(14, 0);
        LocalTime tardeFin = LocalTime.of(17, 0);
        for (int dia = 1; dia <= 5; dia++) {
            horarioRepository.save(Schedule.builder()
                    .medico(medico).diaSemana(dia)
                    .horaInicio(mananaInicio).horaFin(mananaFin).disponible(true).build());
            horarioRepository.save(Schedule.builder()
                    .medico(medico).diaSemana(dia)
                    .horaInicio(tardeInicio).horaFin(tardeFin).disponible(true).build());
        }
    }

    private ScheduleResponse toDto(Schedule h) {
        return ScheduleResponse.builder()
                .id(h.getId())
                .medicoId(h.getMedico().getId())
                .diaSemana(h.getDiaSemana())
                .diaNombre(nombreDia(h.getDiaSemana()))
                .horaInicio(h.getHoraInicio())
                .horaFin(h.getHoraFin())
                .disponible(h.getDisponible())
                .build();
    }

    public static String nombreDia(Integer dia) {
        if (dia == null) return "";
        return switch (dia) {
            case 1 -> "Lunes";
            case 2 -> "Martes";
            case 3 -> "Miércoles";
            case 4 -> "Jueves";
            case 5 -> "Viernes";
            case 6 -> "Sábado";
            case 7 -> "Domingo";
            default -> "Día " + dia;
        };
    }
}
