package com.usuario.Medico.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalTime;

@Data
public class HorarioRequest {
    @NotNull
    private Integer diaSemana;
    @NotNull
    private LocalTime horaInicio;
    @NotNull
    private LocalTime horaFin;
    private Boolean disponible;
}
