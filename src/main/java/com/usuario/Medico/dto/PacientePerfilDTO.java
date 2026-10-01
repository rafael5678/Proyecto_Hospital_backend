package com.usuario.Medico.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

@Data
@Builder
public class PacientePerfilDTO {
    private Long id;
    private Long usuarioId;
    private String nombre;
    private String email;
    private String telefono;
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
