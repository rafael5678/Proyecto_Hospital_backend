package com.hospy.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** JSON body for the skin photo review. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiDermatologyRequest {

    private String descripcion;

    private String imagenBase64;

    private String tiempoEvolucion;

    private String sintomasAsociados;
}
