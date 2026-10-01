package com.usuario.Medico.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalTime;

@Data
@Builder
public class HorarioResponse {
    private Long id;
    private Long medicoId;
    private Integer diaSemana;
    private LocalTime horaInicio;
    private LocalTime horaFin;
    private Boolean disponible;
}
