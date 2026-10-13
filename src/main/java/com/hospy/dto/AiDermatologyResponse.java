package com.hospy.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiDermatologyResponse {

    private String nivelRiesgo;

    private Double scoreRiesgo;

    /** Texto clínico para el paciente: por qué ese nivel, sin jerga de datasets. */
    private String explicacion;

    private List<String> diagnosticosDiferenciales;

    private List<String> caracteristicasObservadas;

    private String recomendaciones;

    private String advertencia;

    private Boolean modoDemo;
}
