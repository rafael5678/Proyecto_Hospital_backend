package com.hospy.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SpecialtyDto {
    private Long id;
    private String nombre;
    private boolean yaExistia;
}
