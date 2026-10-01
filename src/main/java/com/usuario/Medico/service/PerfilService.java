package com.usuario.Medico.service;

import com.usuario.Medico.dto.RegisterRequest;
import com.usuario.Medico.dto.UsuarioRequest;
import com.usuario.Medico.model.*;
import com.usuario.Medico.repository.AdministradorRepository;
import com.usuario.Medico.repository.MedicoRepository;
import com.usuario.Medico.repository.PacienteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class PerfilService {

    private final PacienteRepository pacienteRepository;
    private final MedicoRepository medicoRepository;
    private final AdministradorRepository administradorRepository;

    @Transactional
    public void crearPerfil(Usuario usuario, UsuarioRequest req) {
        switch (usuario.getRol()) {
            case PACIENTE -> pacienteRepository.save(Paciente.builder()
                    .usuario(usuario)
                    .documento(req.getDocumento())
                    .fechaNacimiento(parseFecha(req.getFechaNacimiento()))
                    .genero(req.getGenero())
                    .tipoSangre(req.getTipoSangre())
                    .ciudad(req.getCiudad())
                    .alergias(req.getAlergias())
                    .build());
            case MEDICO -> medicoRepository.save(Medico.builder()
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
    public void crearPaciente(Usuario usuario, RegisterRequest req) {
        if (pacienteRepository.findByUsuario(usuario).isEmpty()) {
            pacienteRepository.save(Paciente.builder()
                    .usuario(usuario)
                    .documento(req.getDocumento())
                    .fechaNacimiento(req.getFechaNacimiento())
                    .genero(req.getGenero())
                    .tipoSangre(req.getTipoSangre())
                    .direccion(req.getDireccion())
                    .ciudad(req.getCiudad())
                    .alergias(req.getAlergias())
                    .contactoEmergencia(req.getContactoEmergencia())
                    .telefonoEmergencia(req.getTelefonoEmergencia())
                    .observaciones(req.getObservaciones())
                    .build());
        }
    }

    @Transactional
    public void actualizarPerfil(Usuario usuario, UsuarioRequest req) {
        switch (usuario.getRol()) {
            case PACIENTE -> pacienteRepository.findByUsuario(usuario).ifPresent(p -> {
                if (req.getDocumento() != null) p.setDocumento(req.getDocumento());
                pacienteRepository.save(p);
            });
            case MEDICO -> medicoRepository.findByUsuario(usuario).ifPresent(m -> {
                if (req.getEspecialidad() != null) m.setEspecialidad(req.getEspecialidad());
                if (req.getNumeroLicencia() != null) m.setNumeroLicencia(req.getNumeroLicencia());
                medicoRepository.save(m);
            });
            case ADMIN -> administradorRepository.findByUsuario(usuario).ifPresent(a -> {
                if (req.getCargo() != null) a.setCargo(req.getCargo());
                administradorRepository.save(a);
            });
        }
    }
}
