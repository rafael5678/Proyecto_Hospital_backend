package com.hospy.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiSoapResponse {

    private String subjetivo;

    private String objetivo;

    private String apreciacion;

    private String plan;

    private String diagnosticoPresuntivo;

    private String procedimientosSugeridos;

    /** Qué le pasa al paciente, escrito para el médico y no como el aviso del triaje. */
    private String resumenParaMedico;

    /** Qué revisar y decidir durante la cita. */
    private String enfoqueEnConsulta;

    /** Una pregunta por línea. */
    private String preguntasSugeridas;

    /** Una opción por línea. El médico elige. */
    private String medicamentosSugeridos;

    /** Ejercicio, cuidados en casa y cuándo volver. */
    private String cuidados;

    private Boolean modoDemo;
}
