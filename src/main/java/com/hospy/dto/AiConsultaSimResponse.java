package com.hospy.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiConsultaSimResponse {
    private String dicePaciente;
    private String pistaParaElMedico;
    /** Una opción por línea. */
    private String medicamentosSugeridos;
    private String cuidados;
    private Boolean modoDemo;
}
