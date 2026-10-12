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
public class AiTriageRequest {

    @NotBlank
    private String sintomas;

    private String duracion;

    private String antecedentes;

    private Integer edadPaciente;

    /** Foto clínica opcional (data URL o base64) para que Gemini vea la zona afectada. */
    private String imagenBase64;
}
