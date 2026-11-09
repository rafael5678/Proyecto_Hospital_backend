package com.hospy.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalTime;

/** Weekly work window mapped to table horarios. */
@Entity
@Table(
        name = "horarios",
        indexes = {
                @Index(name = "idx_horarios_medico", columnList = "medico_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Schedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "medico_id", nullable = false)
    private Doctor medico;

    @Column(nullable = false)
    private Integer diaSemana;

    @Column(nullable = false)
    private LocalTime horaInicio;

    @Column(nullable = false)
    private LocalTime horaFin;

    @Builder.Default
    private Boolean disponible = true;
}
