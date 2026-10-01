package com.usuario.Medico.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class ReporteResponse {
    private long totalCitas;
    private long citasPendientes;
    private long citasAceptadas;
    private long citasCanceladas;
    private long totalPacientes;
    private long totalMedicos;
    private List<Map<String, Object>> desgloseMensual;
}
