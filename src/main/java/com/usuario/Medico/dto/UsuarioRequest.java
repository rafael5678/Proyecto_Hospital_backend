package com.usuario.Medico.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UsuarioRequest {
    @NotBlank
    private String nombre;
    @NotBlank
    @Email
    private String email;
    private String password;
    @NotBlank
    private String rol;
    private String telefono;
    private String documento;
    private String especialidad;
    private String numeroLicencia;
    private String consultorio;
    private Integer anosExperiencia;
    private String biografia;
    private String cargo;
    private String departamento;
    private String extensionTelefonica;
    private Boolean activo;
    private String fechaNacimiento;
    private String genero;
    private String tipoSangre;
    private String ciudad;
    private String alergias;
}
