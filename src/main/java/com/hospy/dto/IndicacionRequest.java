package com.hospy.dto;

import lombok.Data;

@Data
public class IndicacionRequest {
    private String resumen;
    private String medicamentos;
    private String cuidados;
}
