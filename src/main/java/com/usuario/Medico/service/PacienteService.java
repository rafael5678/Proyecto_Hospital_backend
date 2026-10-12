package com.usuario.Medico.service;

import com.usuario.Medico.dto.*;
import com.usuario.Medico.model.*;
import com.usuario.Medico.repository.CitaRepository;
import com.usuario.Medico.repository.PacienteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PacienteService {

    private final PacienteRepository pacienteRepository;
    private final CitaRepository citaRepository;

    public Paciente buscarPorEmail(String email) {
        return pacienteRepository.findByUsuario_Email(email)
                .orElseThrow(() -> new RuntimeException("Paciente no encontrado"));
    }

    public PacientePerfilDTO obtenerPerfil(String email) {
        return toPerfilDto(buscarPorEmail(email));
    }

    @Transactional
    public PacientePerfilDTO actualizarPerfil(String email, PacientePerfilDTO dto) {
        Paciente p = buscarPorEmail(email);
        Usuario u = p.getUsuario();
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

    public PacienteDashboardDTO dashboard(String email) {
        Paciente p = buscarPorEmail(email);
        List<Cita> citas = citaRepository.findByPacienteOrderByFechaHoraDesc(p);
        long pendientes = citas.stream().filter(c -> c.getEstado() == EstadoCita.PENDIENTE).count();
        long aceptadas = citas.stream().filter(c -> c.getEstado() == EstadoCita.ACEPTADA).count();
        long canceladas = citas.stream().filter(c -> c.getEstado() == EstadoCita.CANCELADA).count();
        long proximas = citas.stream()
                .filter(c -> c.getFechaHora().isAfter(LocalDateTime.now())
                        && (c.getEstado() == EstadoCita.PENDIENTE || c.getEstado() == EstadoCita.ACEPTADA))
                .count();
        return PacienteDashboardDTO.builder()
                .totalCitas(citas.size())
                .citasPendientes(pendientes)
                .citasAceptadas(aceptadas)
                .citasCanceladas(canceladas)
                .proximasCitas(proximas)
                .build();
    }

    public PacientePerfilDTO toPerfilDto(Paciente p) {
        Usuario u = p.getUsuario();
        return PacientePerfilDTO.builder()
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
