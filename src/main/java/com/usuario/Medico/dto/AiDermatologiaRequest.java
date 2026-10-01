package com.usuario.Medico.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiDermatologiaRequest {

    @NotBlank
    private String descripcion;

    private String imagenBase64;

    private String tiempoEvolucion;

    private String sintomasAsociados;
}
