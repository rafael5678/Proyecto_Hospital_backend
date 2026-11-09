package com.hospy.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** JSON body for patient triage. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiTriageRequest {

    @NotBlank
    private String sintomas;

    private String duracion;

    private String antecedentes;

    private Integer edadPaciente;

    /** Optional clinical photo (data URL or base64) so Gemini can see the affected area. */
    private String imagenBase64;
}
