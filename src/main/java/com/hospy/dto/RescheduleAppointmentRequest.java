package com.hospy.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RescheduleAppointmentRequest {
    @NotNull
    @Future
    private LocalDateTime fechaHora;

    @NotBlank
    @Size(max = 500)
    private String motivo;
}