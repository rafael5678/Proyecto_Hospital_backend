package com.hospy.service;

import com.hospy.dto.RegisterRequest;
import com.hospy.dto.UserRequest;
import com.hospy.model.*;
import com.hospy.repository.AdministratorRepository;
import com.hospy.repository.DoctorRepository;
import com.hospy.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class ProfileService {

    private final PatientRepository pacienteRepository;
    private final DoctorRepository medicoRepository;
    private final AdministratorRepository administradorRepository;

    @Transactional
    public void crearPerfil(User usuario, UserRequest req) {
        switch (usuario.getRol()) {
            case PACIENTE -> pacienteRepository.save(Patient.builder()
                    .usuario(usuario)
                    .documento(CedulaColombia.normalizar(req.getDocumento()))
                    .fechaNacimiento(parseFecha(req.getFechaNacimiento()))
                    .genero(req.getGenero())
                    .tipoSangre(req.getTipoSangre())
                    .direccion(req.getDireccion())
                    .ciudad(req.getCiudad())
                    .alergias(req.getAlergias())
                    .contactoEmergencia(req.getContactoEmergencia())
                    .telefonoEmergencia(TelefonoColombia.normalizar(req.getTelefonoEmergencia(), "El teléfono de emergencia"))
                    .observaciones(req.getObservaciones())
                    .build());
            case MEDICO -> medicoRepository.save(Doctor.builder()
                    .usuario(usuario)
                    .especialidad(req.getEspecialidad() != null ? req.getEspecialidad() : "General")
                    .numeroLicencia(req.getNumeroLicencia())
                    .consultorio(req.getConsultorio())
                    .anosExperiencia(req.getAnosExperiencia())
                    .biografia(req.getBiografia())
                    .build());
            case ADMIN -> throw new RuntimeException("No se puede crear otro administrador.");
        }
    }

    private static LocalDate parseFecha(String valor) {
        if (valor == null || valor.isBlank()) return null;
        try {
            return LocalDate.parse(valor);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    @Transactional
    public void crearPaciente(User usuario, RegisterRequest req) {
        if (pacienteRepository.findByUsuario(usuario).isEmpty()) {
            pacienteRepository.save(Patient.builder()
                    .usuario(usuario)
                    .documento(CedulaColombia.normalizar(req.getDocumento()))
                    .fechaNacimiento(req.getFechaNacimiento())
                    .genero(req.getGenero())
                    .tipoSangre(req.getTipoSangre())
                    .direccion(req.getDireccion())
                    .ciudad(req.getCiudad())
                    .alergias(req.getAlergias())
                    .contactoEmergencia(req.getContactoEmergencia())
                    .telefonoEmergencia(TelefonoColombia.normalizar(req.getTelefonoEmergencia(), "El teléfono de emergencia"))
                    .observaciones(req.getObservaciones())
                    .build());
        }
    }

    @Transactional
    public void actualizarPerfil(User usuario, UserRequest req) {
        switch (usuario.getRol()) {
            case PACIENTE -> {
                var p = pacienteRepository.findByUsuarioId(usuario.getId())
                        .orElseGet(() -> Patient.builder().usuario(usuario).build());
                if (req.getDocumento() != null) p.setDocumento(CedulaColombia.normalizar(req.getDocumento()));
                if (req.getFechaNacimiento() != null) p.setFechaNacimiento(parseFecha(req.getFechaNacimiento()));
                if (req.getGenero() != null) p.setGenero(req.getGenero());
                if (req.getTipoSangre() != null) p.setTipoSangre(req.getTipoSangre());
                if (req.getDireccion() != null) p.setDireccion(req.getDireccion());
                if (req.getCiudad() != null) p.setCiudad(req.getCiudad());
                if (req.getAlergias() != null) p.setAlergias(req.getAlergias());
                if (req.getContactoEmergencia() != null) p.setContactoEmergencia(req.getContactoEmergencia());
                if (req.getTelefonoEmergencia() != null) {
                    p.setTelefonoEmergencia(TelefonoColombia.normalizar(req.getTelefonoEmergencia(), "El teléfono de emergencia"));
                }
                if (req.getObservaciones() != null) p.setObservaciones(req.getObservaciones());
                pacienteRepository.save(p);
            }
            case MEDICO -> medicoRepository.findByUsuario(usuario).ifPresent(m -> {
                if (req.getEspecialidad() != null && !req.getEspecialidad().isBlank()) m.setEspecialidad(req.getEspecialidad());
                if (req.getNumeroLicencia() != null) m.setNumeroLicencia(req.getNumeroLicencia());
                if (req.getConsultorio() != null) m.setConsultorio(req.getConsultorio());
                if (req.getAnosExperiencia() != null) m.setAnosExperiencia(req.getAnosExperiencia());
                if (req.getBiografia() != null) m.setBiografia(req.getBiografia());
                medicoRepository.save(m);
            });
            case ADMIN -> administradorRepository.findByUsuario(usuario).ifPresent(a -> {
                if (req.getCargo() != null) a.setCargo(req.getCargo());
                administradorRepository.save(a);
            });
        }
    }
}
