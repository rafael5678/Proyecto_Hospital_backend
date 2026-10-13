package com.hospy.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "administradores")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Administrator {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "usuario_id", nullable = false, unique = true)
    private User usuario;

    private String cargo;
    private String departamento;
    private String extensionTelefonica;
}
