package com.hospy.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AiAdminOperationRequest {
    @NotBlank
    private String instruccion;

    /** JSON del alta en curso, para completar datos en el siguiente mensaje. */
    private String borrador;
}
