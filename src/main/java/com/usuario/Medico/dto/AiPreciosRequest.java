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
public class AiPreciosRequest {

    @NotBlank
    private String medicamento;

    private String presentacion;

    private Double precioReportado;

    private String ciudad;
}
