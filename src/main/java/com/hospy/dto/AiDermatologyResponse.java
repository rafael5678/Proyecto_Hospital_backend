package com.hospy.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** JSON skin review the portals already read. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiDermatologyResponse {

    private String nivelRiesgo;

    private Double scoreRiesgo;

    /** Patient-facing clinical text: why that risk level, without dataset jargon. */
    private String explicacion;

    private List<String> diagnosticosDiferenciales;

    private List<String> caracteristicasObservadas;

    private String recomendaciones;

    private String advertencia;

    private Boolean modoDemo;
}
