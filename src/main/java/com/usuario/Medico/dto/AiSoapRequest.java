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
public class AiSoapRequest {

    @NotBlank
    private String transcripcionConsulta;

    private String nombrePaciente;

    private String motivoInicial;
}
