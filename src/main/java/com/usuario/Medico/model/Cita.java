package com.usuario.Medico.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "citas")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Cita {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "paciente_id", nullable = false)
    private Paciente paciente;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "medico_id", nullable = false)
    private Medico medico;

    @Column(nullable = false)
    private LocalDateTime fechaHora;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private EstadoCita estado = EstadoCita.PENDIENTE;

    private String motivo;

    private String notas;

    /* ====== Campos IA adjuntos al momento de AGENDAR (guardados automáticamente) ====== */
    /* Triage NLP BERT/RoBERTa (ESI/MTS) */
    private String triageSeveridad;        /* "ROJO / NARANJA / AMARILLO / VERDE / AZUL" */
    private Integer triageNivelEsi;        /* 1-5, Emergency Severity Index */
    private Integer triagePrioridad;       /* 1-10 */
    private String triageEspecialidadSugerida;  /* "Dermatología", "Medicina General"... */
    @Column(columnDefinition = "TEXT")
    private String triageResumen;          /* Resumen clínico IA que lee el médico */
    @Column(columnDefinition = "TEXT")
    private String triageSintomas;
    @Column(length = 255)
    private String triageDuracion;
    @Column(columnDefinition = "TEXT")
    private String triageAntecedentes;

    /* Predermatología CNN ResNet/EfficientNet */
    @Column(columnDefinition = "TEXT")
    private String dermatologiaReportaIa;  /* Reporte completo + diferenciales + recomendaciones */
    private Double dermatologiaScoreRiesgo; /* 0.0 a 1.0 */
    private String dermatologiaTopDiagnostico; /* Diagnóstico principal sugerido */
}
