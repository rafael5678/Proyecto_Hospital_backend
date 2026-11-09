package com.hospy.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalTime;

/** JSON body to save a doctor work window. */
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
