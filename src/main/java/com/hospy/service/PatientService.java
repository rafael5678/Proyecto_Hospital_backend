package com.hospy.service;

import com.hospy.dto.*;
import com.hospy.model.*;
import com.hospy.repository.AppointmentRepository;
import com.hospy.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PatientService {

    private final PatientRepository pacienteRepository;
    private final AppointmentRepository citaRepository;

    public Patient buscarPorEmail(String email) {
        return pacienteRepository.findByUsuario_Email(email)
                .orElseThrow(() -> new RuntimeException("Patient no encontrado"));
    }

    public PatientProfileDto obtenerPerfil(String email) {
        return toPerfilDto(buscarPorEmail(email));
    }

    @Transactional
    public PatientProfileDto actualizarPerfil(String email, PatientProfileDto dto) {
        Patient p = buscarPorEmail(email);
        User u = p.getUsuario();
        if (dto.getNombre() != null) u.setNombre(dto.getNombre());
        if (dto.getTelefono() != null) u.setTelefono(dto.getTelefono());
        if (dto.getDocumento() != null) p.setDocumento(dto.getDocumento());
        if (dto.getFechaNacimiento() != null) p.setFechaNacimiento(dto.getFechaNacimiento());
        if (dto.getGenero() != null) p.setGenero(dto.getGenero());
        if (dto.getTipoSangre() != null) p.setTipoSangre(dto.getTipoSangre());
        if (dto.getDireccion() != null) p.setDireccion(dto.getDireccion());
        if (dto.getCiudad() != null) p.setCiudad(dto.getCiudad());
        if (dto.getAlergias() != null) p.setAlergias(dto.getAlergias());
        if (dto.getContactoEmergencia() != null) p.setContactoEmergencia(dto.getContactoEmergencia());
        if (dto.getTelefonoEmergencia() != null) p.setTelefonoEmergencia(dto.getTelefonoEmergencia());
        if (dto.getObservaciones() != null) p.setObservaciones(dto.getObservaciones());
        return toPerfilDto(pacienteRepository.save(p));
    }

    public PatientDashboardDto dashboard(String email) {
        Patient p = buscarPorEmail(email);
        List<Appointment> citas = citaRepository.findByPacienteOrderByFechaHoraDesc(p);
        long pendientes = citas.stream().filter(c -> c.getEstado() == AppointmentStatus.PENDIENTE).count();
        long aceptadas = citas.stream().filter(c -> c.getEstado() == AppointmentStatus.ACEPTADA).count();
        long canceladas = citas.stream().filter(c -> c.getEstado() == AppointmentStatus.CANCELADA).count();
        long proximas = citas.stream()
                .filter(c -> c.getFechaHora().isAfter(LocalDateTime.now())
                        && (c.getEstado() == AppointmentStatus.PENDIENTE || c.getEstado() == AppointmentStatus.ACEPTADA))
                .count();
        return PatientDashboardDto.builder()
                .totalCitas(citas.size())
                .citasPendientes(pendientes)
                .citasAceptadas(aceptadas)
                .citasCanceladas(canceladas)
                .proximasCitas(proximas)
                .build();
    }

    public PatientProfileDto toPerfilDto(Patient p) {
        User u = p.getUsuario();
        return PatientProfileDto.builder()
                .id(p.getId())
                .usuarioId(u.getId())
                .nombre(u.getNombre())
                .email(u.getEmail())
                .telefono(u.getTelefono())
                .documento(p.getDocumento())
                .fechaNacimiento(p.getFechaNacimiento())
                .genero(p.getGenero())
                .tipoSangre(p.getTipoSangre())
                .direccion(p.getDireccion())
                .ciudad(p.getCiudad())
                .alergias(p.getAlergias())
                .contactoEmergencia(p.getContactoEmergencia())
                .telefonoEmergencia(p.getTelefonoEmergencia())
                .observaciones(p.getObservaciones())
                .build();
    }
}
