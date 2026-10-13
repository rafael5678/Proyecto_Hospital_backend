package com.hospy.service;

import com.hospy.dto.DoctorDashboardDto;
import com.hospy.dto.DoctorProfileDto;
import com.hospy.model.*;
import com.hospy.repository.AppointmentRepository;
import com.hospy.repository.ScheduleRepository;
import com.hospy.repository.DoctorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DoctorProfileService {

    private final DoctorRepository medicoRepository;
    private final AppointmentRepository citaRepository;
    private final ScheduleRepository horarioRepository;

    public Doctor buscarPorEmail(String email) {
        return medicoRepository.findByUsuario_Email(email)
                .orElseThrow(() -> new RuntimeException("Médico no encontrado"));
    }

    public DoctorProfileDto obtenerPerfil(String email) {
        return toDto(buscarPorEmail(email));
    }

    public DoctorDashboardDto dashboard(String email) {
        Doctor m = buscarPorEmail(email);
        List<Appointment> citas = citaRepository.findByMedicoOrderByFechaHoraAsc(m);
        LocalDate hoy = LocalDate.now();
        long hoyCount = citas.stream()
                .filter(c -> c.getFechaHora().toLocalDate().equals(hoy))
                .count();
        return DoctorDashboardDto.builder()
                .totalCitas(citas.size())
                .citasPendientes(citas.stream().filter(c -> c.getEstado() == AppointmentStatus.PENDIENTE).count())
                .citasAceptadas(citas.stream().filter(c -> c.getEstado() == AppointmentStatus.ACEPTADA).count())
                .citasHoy(hoyCount)
                .horariosActivos(horarioRepository.findByMedicoAndDisponibleTrue(m).size())
                .build();
    }

    public DoctorProfileDto toDto(Doctor m) {
        User u = m.getUsuario();
        return DoctorProfileDto.builder()
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
