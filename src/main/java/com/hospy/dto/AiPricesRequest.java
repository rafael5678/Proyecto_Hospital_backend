package com.hospy.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiPricesRequest {

    @NotBlank
    private String medicamento;

    private String presentacion;

    private Double precioReportado;

    private String ciudad;
}
