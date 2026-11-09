package com.hospy.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** JSON SOAP helper the doctor portal already reads. */
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

    /** What is going on, written for the doctor, not as the triage notice. */
    private String resumenParaMedico;

    /** What to review and decide during the visit. */
    private String enfoqueEnConsulta;

    /** One suggested question per line. */
    private String preguntasSugeridas;

    /** One option per line. The doctor chooses. */
    private String medicamentosSugeridos;

    /** Home care, exercise, and when to come back. */
    private String cuidados;

    private Boolean modoDemo;
}
