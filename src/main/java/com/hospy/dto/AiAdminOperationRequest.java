package com.hospy.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** JSON body for Registro Hospy chat. */
@Data
public class AiAdminOperationRequest {
    @NotBlank
    private String instruccion;

    /** JSON of the signup in progress, so the next message can fill missing fields. */
    private String borrador;

    /** Earlier admin messages in this same registration, in order. */
    private java.util.List<String> historial;
}
