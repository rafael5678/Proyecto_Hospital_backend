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
