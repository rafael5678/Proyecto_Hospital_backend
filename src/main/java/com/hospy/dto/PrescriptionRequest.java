package com.hospy.dto;

import lombok.Data;

/** JSON body when the doctor sends the visit. */
@Data
public class PrescriptionRequest {
    private String resumen;
    private String medicamentos;
    private String cuidados;
}
