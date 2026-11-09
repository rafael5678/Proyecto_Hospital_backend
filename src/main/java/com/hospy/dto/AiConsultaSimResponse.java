package com.hospy.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** JSON visit chat reply. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiConsultaSimResponse {
    private String dicePaciente;
    private String pistaParaElMedico;
    /** One medication option per line. */
    private String medicamentosSugeridos;
    private String cuidados;
    private Boolean modoDemo;
}
