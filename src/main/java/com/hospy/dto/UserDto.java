package com.hospy.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserDto {
    private Long id;
    private Long usuarioId;
    private String nombre;
    private String email;
    private String rol;
    private String telefono;
    private String documento;
    private String especialidad;
    private String consultorio;
    private Integer anosExperiencia;
    private String biografia;
    private String cargo;
    private String departamento;
    private Boolean activo;
}
