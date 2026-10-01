package com.usuario.Medico.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
public class RegisterRequest {
    @NotBlank
    private String nombre;
    @NotBlank
    @Email
    private String email;
    @NotBlank
    @Size(min = 6)
    private String password;
    private String telefono;
    @NotBlank
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
