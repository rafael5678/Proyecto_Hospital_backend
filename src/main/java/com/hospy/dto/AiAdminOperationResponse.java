package com.hospy.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiAdminOperationResponse {
    /** CREAR_MEDICO | CREAR_PACIENTE | PREGUNTAR | INFO */
    private String accion;
    private String nombre;
    private String email;
    private String especialidad;
    private String passwordSugerida;
    private String documento;
    private String telefono;
    private String numeroLicencia;
    private String consultorio;
    private Integer anosExperiencia;
    private String biografia;
    private String fechaNacimiento;
    private String genero;
    private String ciudad;
    private String alergias;
    private String mensaje;
    private Boolean modoDemo;
}
