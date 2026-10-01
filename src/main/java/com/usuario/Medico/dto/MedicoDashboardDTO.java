package com.usuario.Medico.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MedicoDashboardDTO {
    private long totalCitas;
    private long citasPendientes;
    private long citasAceptadas;
    private long citasHoy;
    private long horariosActivos;
}
