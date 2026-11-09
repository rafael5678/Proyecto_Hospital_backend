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

    /** Usual box or presentation when the doctor did not write one. */
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
        /** Place in the city: mall, neighborhood, or avenue. */
        private String lugar;
        private String presentacion;
    }
}
