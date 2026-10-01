package com.usuario.Medico.dto;

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

    private Boolean modoDemo;
}
