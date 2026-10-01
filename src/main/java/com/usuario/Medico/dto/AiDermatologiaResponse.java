package com.usuario.Medico.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiDermatologiaResponse {

    private String nivelRiesgo;

    private Double scoreRiesgo;

    private List<String> diagnosticosDiferenciales;

    private List<String> caracteristicasObservadas;

    private String recomendaciones;

    private String advertencia;

    private Boolean modoDemo;
}
