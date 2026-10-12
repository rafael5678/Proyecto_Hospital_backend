package com.usuario.Medico.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AiAdminOperacionRequest {
    @NotBlank
    private String instruccion;
}
