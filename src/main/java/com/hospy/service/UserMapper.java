package com.hospy.service;

import com.hospy.dto.UserDto;
import com.hospy.model.*;

public final class UserMapper {

    private UserMapper() {}

    public static UserDto fromUsuario(User u) {
        return UserDto.builder()
                .id(u.getId())
                .usuarioId(u.getId())
                .nombre(u.getNombre())
                .email(u.getEmail())
                .rol(u.getRol().name())
                .telefono(u.getTelefono())
                .activo(u.getActivo())
                .build();
    }

    public static UserDto fromPaciente(Patient p) {
        UserDto dto = fromUsuario(p.getUsuario());
        dto.setId(p.getId());
        dto.setUsuarioId(p.getUsuario().getId());
        dto.setDocumento(p.getDocumento());
        dto.setTelefono(p.getUsuario().getTelefono());
        return dto;
    }

    public static UserDto fromMedico(Doctor m) {
        UserDto dto = fromUsuario(m.getUsuario());
        dto.setId(m.getId());
        dto.setUsuarioId(m.getUsuario().getId());
        dto.setEspecialidad(m.getEspecialidad());
        dto.setConsultorio(m.getConsultorio());
        dto.setAnosExperiencia(m.getAnosExperiencia());
        dto.setBiografia(m.getBiografia());
        return dto;
    }

    public static UserDto fromAdministrador(Administrator a) {
        UserDto dto = fromUsuario(a.getUsuario());
        dto.setId(a.getId());
        dto.setUsuarioId(a.getUsuario().getId());
        return dto;
    }

    /** Para HU-10: info del paciente en cita */
    public static UserDto fromPacientePerfil(Patient p) {
        return fromPaciente(p);
    }
}
