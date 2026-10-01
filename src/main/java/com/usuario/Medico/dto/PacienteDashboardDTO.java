package com.usuario.Medico.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PacienteDashboardDTO {
    private long totalCitas;
    private long citasPendientes;
    private long citasAceptadas;
    private long citasCanceladas;
    private long proximasCitas;
}
