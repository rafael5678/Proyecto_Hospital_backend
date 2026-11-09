package com.hospy.model;

import jakarta.persistence.*;
import lombok.*;

/** Specialty catalog row mapped to table especialidades. */
@Entity
@Table(name = "especialidades")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Specialty {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 80)
    private String nombre;
}
