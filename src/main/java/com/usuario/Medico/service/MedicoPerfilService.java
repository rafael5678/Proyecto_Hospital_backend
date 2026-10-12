package com.usuario.Medico.service;

import com.usuario.Medico.dto.MedicoDashboardDTO;
import com.usuario.Medico.dto.MedicoPerfilDTO;
import com.usuario.Medico.model.*;
import com.usuario.Medico.repository.CitaRepository;
import com.usuario.Medico.repository.HorarioRepository;
import com.usuario.Medico.repository.MedicoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MedicoPerfilService {

    private final MedicoRepository medicoRepository;
    private final CitaRepository citaRepository;
    private final HorarioRepository horarioRepository;

    public Medico buscarPorEmail(String email) {
        return medicoRepository.findByUsuario_Email(email)
                .orElseThrow(() -> new RuntimeException("Médico no encontrado"));
    }

    public MedicoPerfilDTO obtenerPerfil(String email) {
        return toDto(buscarPorEmail(email));
    }

    public MedicoDashboardDTO dashboard(String email) {
        Medico m = buscarPorEmail(email);
        List<Cita> citas = citaRepository.findByMedicoOrderByFechaHoraAsc(m);
        LocalDate hoy = LocalDate.now();
        long hoyCount = citas.stream()
                .filter(c -> c.getFechaHora().toLocalDate().equals(hoy))
                .count();
        return MedicoDashboardDTO.builder()
                .totalCitas(citas.size())
                .citasPendientes(citas.stream().filter(c -> c.getEstado() == EstadoCita.PENDIENTE).count())
                .citasAceptadas(citas.stream().filter(c -> c.getEstado() == EstadoCita.ACEPTADA).count())
                .citasHoy(hoyCount)
                .horariosActivos(horarioRepository.findByMedicoAndDisponibleTrue(m).size())
                .build();
    }

    public MedicoPerfilDTO toDto(Medico m) {
        Usuario u = m.getUsuario();
        return MedicoPerfilDTO.builder()
                .id(m.getId())
                .usuarioId(u.getId())
                .nombre(u.getNombre())
                .email(u.getEmail())
                .telefono(u.getTelefono())
                .especialidad(m.getEspecialidad())
                .numeroLicencia(m.getNumeroLicencia())
                .consultorio(m.getConsultorio())
                .anosExperiencia(m.getAnosExperiencia())
                .biografia(m.getBiografia())
                .build();
    }
}
