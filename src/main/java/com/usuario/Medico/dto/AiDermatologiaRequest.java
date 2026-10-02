package com.usuario.Medico.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiDermatologiaRequest {

    private String descripcion;

    private String imagenBase64;

    private String tiempoEvolucion;

    private String sintomasAsociados;
}
