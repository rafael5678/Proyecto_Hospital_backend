package com.hospy.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Visit mapped to table citas. fechaHora is Colombia wall clock. */
@Entity
@Table(
        name = "citas",
        indexes = {
                @Index(name = "idx_citas_fecha_hora", columnList = "fecha_hora"),
                @Index(name = "idx_citas_estado", columnList = "estado"),
                @Index(name = "idx_citas_paciente", columnList = "paciente_id"),
                @Index(name = "idx_citas_medico", columnList = "medico_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Appointment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "paciente_id", nullable = false)
    private Patient paciente;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "medico_id", nullable = false)
    private Doctor medico;

    @Column(nullable = false)
    private LocalDateTime fechaHora;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private AppointmentStatus estado = AppointmentStatus.PENDIENTE;

    private String motivo;

    private String notas;

    @Column(columnDefinition = "TEXT")
    private String receta;

    private LocalDateTime concluidaEn;

    /* Assistant fields stored when the patient books. */
    private String triageSeveridad;
    private Integer triageNivelEsi;
    private Integer triagePrioridad;
    private String triageEspecialidadSugerida;
    @Column(columnDefinition = "TEXT")
    private String triageResumen;
    @Column(columnDefinition = "TEXT")
    private String triageSintomas;
    @Column(length = 255)
    private String triageDuracion;
    @Column(columnDefinition = "TEXT")
    private String triageAntecedentes;

    @Column(columnDefinition = "TEXT")
    private String dermatologiaReportaIa;
    private Double dermatologiaScoreRiesgo;
    private String dermatologiaTopDiagnostico;
}
