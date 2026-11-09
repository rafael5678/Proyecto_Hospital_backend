package com.hospy.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** JSON body for the SOAP consult helper. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiSoapRequest {

    @NotBlank
    private String transcripcionConsulta;

    private String nombrePaciente;

    private String motivoInicial;
}
