package com.hospy.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** JSON body for drug and food interaction checks. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiInteractionRequest {

    @NotEmpty
    private List<String> medicamentos;

    private List<String> dietaHabitual;

    private List<String> suplementos;
}
