package com.usuario.Medico.dto;

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
public class AiDermatologiaResponse {

    private String nivelRiesgo;

    private Double scoreRiesgo;

    private List<String> diagnosticosDiferenciales;

    private List<String> caracteristicasObservadas;

    private String recomendaciones;

    private String advertencia;

    private Boolean modoDemo;
}
