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
public class AiInteraccionResponse {

    private String nivelRiesgoGlobal;

    private List<AlertaInteraccion> alertas;

    private List<String> recomendacionesDieteticas;

    private String resumen;

    private Boolean modoDemo;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AlertaInteraccion {
        private String elementos;
        private String tipo;
        private String severidad;
        private String mecanismo;
        private String consecuencia;
        private String accion;
    }
}
