package com.usuario.Medico.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "notificaciones")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notificacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @Column(nullable = false, length = 160)
    private String titulo;

    @Column(nullable = false, length = 800)
    private String mensaje;

    @Column(nullable = false, length = 40)
    private String tipo;

    @Builder.Default
    private Boolean leida = false;

    private Long citaId;

    @Builder.Default
    private LocalDateTime creadaEn = LocalDateTime.now();
}
