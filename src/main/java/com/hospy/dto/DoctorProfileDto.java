package com.hospy.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DoctorProfileDto {
    private Long id;
    private Long usuarioId;
    private String nombre;
    private String email;
    private String telefono;
    private String especialidad;
    private String numeroLicencia;
    private String consultorio;
    private Integer anosExperiencia;
    private String biografia;
}
