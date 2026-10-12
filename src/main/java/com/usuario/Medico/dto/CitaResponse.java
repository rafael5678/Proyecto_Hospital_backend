package com.usuario.Medico.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class CitaResponse {
    private Long id;
    private Long pacienteId;
    private String pacienteNombre;
    private String pacienteDocumento;
    private Long medicoId;
    private String medicoNombre;
    private String medicoEspecialidad;
    private LocalDateTime fechaHora;
    private String estado;
    private String motivo;
    private String notas;
    private String triageSeveridad;
    private Integer triageNivelEsi;
    private Integer triagePrioridad;
    private String triageEspecialidadSugerida;
    private String triageResumen;
    private String triageSintomas;
    private String triageDuracion;
    private String triageAntecedentes;
    private String dermatologiaReportaIa;
    private Double dermatologiaScoreRiesgo;
    private String dermatologiaTopDiagnostico;
    private String avisoAgenda;
}
