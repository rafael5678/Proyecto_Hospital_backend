package com.hospy.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

/** JSON body to book a visit. */
@Data
public class AppointmentRequest {
    @NotNull
    private Long medicoId;
    /** If missing, the app assigns the doctor's next open work slot. */
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
