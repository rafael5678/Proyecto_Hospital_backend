package com.usuario.Medico.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CitaRequest {
    @NotNull
    private Long medicoId;
    @NotNull
    private LocalDateTime fechaHora;
    private String motivo;
    private String notas;

    /* ====== Campos IA opcionales (todos nullables) ====== */
    /* Triage NLP */
    private String triageSeveridad;
    private Integer triageNivelEsi;
    private Integer triagePrioridad;
    private String triageEspecialidadSugerida;
    private String triageResumen;
    private String triageSintomas;
    private String triageDuracion;
    private String triageAntecedentes;

    /* Dermatología CNN */
    private String dermatologiaReportaIa;
    private Double dermatologiaScoreRiesgo;
    private String dermatologiaTopDiagnostico;
}

// DTO Request: Transporta los parámetros necesarios para agendar o solicitar una cita médica.
