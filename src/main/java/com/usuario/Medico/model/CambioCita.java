package com.usuario.Medico.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "cambios_cita")
@Getter
@Setter
@NoArgsConstructor
public class CambioCita {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cita_id", nullable = false)
    private Cita cita;

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