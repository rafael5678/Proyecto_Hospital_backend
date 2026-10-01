package com.usuario.Medico.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class AiMetricasResponse {
    /* Conteos reales de BD */
    private long totalCitas;
    private long citasConTriage;
    private long citasConEvaluacionPiel;
    private long citasPrioridadAlta;
    private long citasReprogramadasPorIA;

    /* Distribución de severidad triage */
    private Map<String, Long> distribucionSeveridad;  /* Rojo:2, Naranja:5, Amarillo:12... */

    /* Estado de IA */
    private boolean apiKeyActiva;
    private String versionIa;
    private String estadoWhisper;
    private String estadoSoap;
    private String estadoTriage;
    private String estadoDermatologia;
    private String estadoInteracciones;
    private String estadoPrecios;

    /* Seguridad */
    private int timeoutMedicoSeg;
    private int timeoutAdminSeg;
}
