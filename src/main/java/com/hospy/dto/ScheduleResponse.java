package com.hospy.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalTime;

@Data
@Builder
public class ScheduleResponse {
    private Long id;
    private Long medicoId;
    private Integer diaSemana;
    private String diaNombre;
    private LocalTime horaInicio;
    private LocalTime horaFin;
    private Boolean disponible;
}
