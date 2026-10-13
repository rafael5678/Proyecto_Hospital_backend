package com.hospy.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SlotResponse {
    private LocalDateTime fechaHora;
    private String etiqueta;
    private String dia;
    private boolean ocupadoMenorPrioridad;
}
