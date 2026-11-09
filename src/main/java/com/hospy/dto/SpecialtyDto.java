package com.hospy.dto;

import lombok.Builder;
import lombok.Data;

/** JSON specialty catalog row. */
@Data
@Builder
public class SpecialtyDto {
    private Long id;
    private String nombre;
    private boolean yaExistia;
}
