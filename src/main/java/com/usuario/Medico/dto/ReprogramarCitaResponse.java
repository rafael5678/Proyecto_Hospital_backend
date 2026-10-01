package com.usuario.Medico.dto;

public record ReprogramarCitaResponse(
        CitaResponse cita,
        boolean correoPacienteEnviado,
        boolean correoMedicoEnviado,
        String mensaje
) { }