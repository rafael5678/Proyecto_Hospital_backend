package com.hospy.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "cambios_cita", indexes = {
        @Index(name = "idx_cambios_cita_cita", columnList = "cita_id")
})
@Getter
@Setter
@NoArgsConstructor
public class AppointmentChange {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cita_id", nullable = false)
    private Appointment cita;

    @Column(nullable = false)
    private LocalDateTime fechaAnterior;

    @Column(nullable = false)
    private LocalDateTime fechaNueva;

    @Column(nullable = false, length = 500)
    private String motivo;

    @Column(nullable = false, length = 255)
    private String realizadoPor;

    @Column(nullable = false)
    private LocalDateTime creadoEn;

    @Column(nullable = false)
    private boolean correoPacienteEnviado;

    @Column(nullable = false)
    private boolean correoMedicoEnviado;

    @PrePersist
    void onCreate() {
        creadoEn = LocalDateTime.now();
    }
}