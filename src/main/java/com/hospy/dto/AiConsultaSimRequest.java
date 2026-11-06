package com.hospy.dto;

import lombok.Data;

import java.util.List;

@Data
public class AiConsultaSimRequest {
    private String nombrePaciente;
    private String motivo;
    private String contexto;
    private String pregunta;
    private List<String> dialogo;
}
