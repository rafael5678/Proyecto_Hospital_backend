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
public class AiTriageResponse {

    private String severidad;

    private String escala;

    private Integer nivelEsi;

    private String especialidadRecomendada;

    private Integer prioridad;

    private List<String> hallazgos;

    private String recomendaciones;

    private String resumen;

    private Boolean modoDemo;
}
