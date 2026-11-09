package com.hospy.service;

import com.hospy.dto.UserDto;
import com.hospy.model.*;

/** Maps User entities to JSON the portals already expect. */
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
        if (p.getFechaNacimiento() != null) dto.setFechaNacimiento(p.getFechaNacimiento().toString());
        dto.setGenero(p.getGenero());
        dto.setTipoSangre(p.getTipoSangre());
        dto.setDireccion(p.getDireccion());
        dto.setCiudad(p.getCiudad());
        dto.setAlergias(p.getAlergias());
        dto.setContactoEmergencia(p.getContactoEmergencia());
        dto.setTelefonoEmergencia(p.getTelefonoEmergencia());
        dto.setObservaciones(p.getObservaciones());
        return dto;
    }

    public static UserDto fromMedico(Doctor m) {
        UserDto dto = fromUsuario(m.getUsuario());
        dto.setId(m.getId());
        dto.setUsuarioId(m.getUsuario().getId());
        dto.setEspecialidad(m.getEspecialidad());
        dto.setNumeroLicencia(m.getNumeroLicencia());
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

    /** Patient snapshot attached to a visit. */
    public static UserDto fromPacientePerfil(Patient p) {
        return fromPaciente(p);
    }
}
