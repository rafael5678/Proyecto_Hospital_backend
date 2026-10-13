package com.hospy.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AiAdminOperationRequest {
    @NotBlank
    private String instruccion;
}
