package com.usuario.Medico.service;

import com.usuario.Medico.dto.UsuarioDTO;
import com.usuario.Medico.model.*;

public final class UsuarioMapper {

    private UsuarioMapper() {}

    public static UsuarioDTO fromUsuario(Usuario u) {
        return UsuarioDTO.builder()
                .id(u.getId())
                .usuarioId(u.getId())
                .nombre(u.getNombre())
                .email(u.getEmail())
                .rol(u.getRol().name())
                .telefono(u.getTelefono())
                .activo(u.getActivo())
                .build();
    }

    public static UsuarioDTO fromPaciente(Paciente p) {
        UsuarioDTO dto = fromUsuario(p.getUsuario());
        dto.setId(p.getId());
        dto.setUsuarioId(p.getUsuario().getId());
        dto.setDocumento(p.getDocumento());
        dto.setTelefono(p.getUsuario().getTelefono());
        return dto;
    }

    public static UsuarioDTO fromMedico(Medico m) {
        UsuarioDTO dto = fromUsuario(m.getUsuario());
        dto.setId(m.getId());
        dto.setUsuarioId(m.getUsuario().getId());
        dto.setEspecialidad(m.getEspecialidad());
        dto.setConsultorio(m.getConsultorio());
        dto.setAnosExperiencia(m.getAnosExperiencia());
        dto.setBiografia(m.getBiografia());
        return dto;
    }

    public static UsuarioDTO fromAdministrador(Administrador a) {
        UsuarioDTO dto = fromUsuario(a.getUsuario());
        dto.setId(a.getId());
        dto.setUsuarioId(a.getUsuario().getId());
        return dto;
    }

    /** Para HU-10: info del paciente en cita */
    public static UsuarioDTO fromPacientePerfil(Paciente p) {
        return fromPaciente(p);
    }
}
