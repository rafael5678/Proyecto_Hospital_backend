package com.hospy.dto;

import lombok.Builder;
import lombok.Data;

/** JSON user row shown in the admin list. */
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
    private String fechaNacimiento;
    private String genero;
    private String tipoSangre;
    private String direccion;
    private String ciudad;
    private String alergias;
    private String contactoEmergencia;
    private String telefonoEmergencia;
    private String observaciones;
    private String especialidad;
    private String numeroLicencia;
    private String consultorio;
    private Integer anosExperiencia;
    private String biografia;
    private String diasAtencion;
    private String horaInicio;
    private String horaFin;
    private String horaInicio2;
    private String horaFin2;
    /** Filled only on the admin list. Stored encrypted in the database. */
    private String clave;
    private String cargo;
    private String departamento;
    private Boolean activo;
}
