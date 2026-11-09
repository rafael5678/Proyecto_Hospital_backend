package com.hospy.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Bell notice mapped to table notificaciones. */
@Entity
@Table(
        name = "notificaciones",
        indexes = {
                @Index(name = "idx_notificaciones_usuario", columnList = "usuario_id"),
                @Index(name = "idx_notificaciones_leida", columnList = "leida")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id", nullable = false)
    private User usuario;

    @Column(nullable = false, length = 160)
    private String titulo;

    @Column(nullable = false, length = 4000)
    private String mensaje;

    @Column(nullable = false, length = 40)
    private String tipo;

    @Builder.Default
    private Boolean leida = false;

    private Long citaId;

    @Builder.Default
    private LocalDateTime creadaEn = LocalDateTime.now();
}
