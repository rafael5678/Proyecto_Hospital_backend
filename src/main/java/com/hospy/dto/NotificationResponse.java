package com.hospy.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** JSON bell notice. */
@Data
@Builder
public class NotificationResponse {
    private Long id;
    private String titulo;
    private String mensaje;
    private String tipo;
    private Boolean leida;
    private Long citaId;
    private LocalDateTime creadaEn;
}
