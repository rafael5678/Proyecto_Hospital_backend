package com.usuario.Medico.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "pacientes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Paciente {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "usuario_id", nullable = false, unique = true)
    private Usuario usuario;

    private String documento;
    private LocalDate fechaNacimiento;
    private String genero;
    private String tipoSangre;
    private String direccion;
    private String ciudad;
    private String alergias;
    private String contactoEmergencia;
    private String telefonoEmergencia;
    private String observaciones;
}
