package com.hospy.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/** JSON admin AI metrics. */
@Data
@Builder
public class AiMetricsResponse {
    /* Counts from stored visits. */
    private long totalCitas;
    private long citasConTriage;
    private long citasConEvaluacionPiel;
    private long citasPrioridadAlta;
    private long citasReprogramadasPorIA;

    /* Triage severity map. */
    private Map<String, Long> distribucionSeveridad;  /* Rojo:2, Naranja:5, Amarillo:12... */

    /* Assistant status shown on the admin board. */
    private boolean apiKeyActiva;
    private String versionIa;
    private String estadoWhisper;
    private String estadoSoap;
    private String estadoTriage;
    private String estadoDermatologia;
    private String estadoInteracciones;
    private String estadoPrecios;

    /* Idle timeouts in seconds. */
    private int timeoutMedicoSeg;
    private int timeoutAdminSeg;
}
