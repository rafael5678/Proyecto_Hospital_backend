package com.hospy.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiPricesResponse {

    private Double precioEsperadoMin;
    private Double precioEsperadoMax;
    private Double precioPromedio;

    private String evaluacionSobreprecio;
    private Double porcentajeDesviacion;

    private List<ComparativaFarmacia> comparativa;

    private List<String> alertas;

    private String recomendacion;

    /** Caja o presentación más habitual cuando el médico no la escribió. */
    private String presentacionEstimada;

    private Boolean modoDemo;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ComparativaFarmacia {
        private String nombre;
        private Double precio;
        private String disponibilidad;
        private String url;
        /** Sitio de la ciudad: centro comercial, barrio o avenida. */
        private String lugar;
        private String presentacion;
    }
}
