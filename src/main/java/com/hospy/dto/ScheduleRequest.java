package com.hospy.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalTime;

@Data
public class ScheduleRequest {
    @NotNull
    private Integer diaSemana;
    @NotNull
    private LocalTime horaInicio;
    @NotNull
    private LocalTime horaFin;
    private Boolean disponible;
}
