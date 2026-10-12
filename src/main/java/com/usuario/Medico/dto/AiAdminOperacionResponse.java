package com.usuario.Medico.dto;

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
public class AiAdminOperacionResponse {
    /** CREAR_MEDICO | CREAR_PACIENTE | INFO */
    private String accion;
    private String nombre;
    private String email;
    private String especialidad;
    private String passwordSugerida;
    private String documento;
    private String telefono;
    private String mensaje;
    private Boolean modoDemo;
}
