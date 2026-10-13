package com.hospy.dto;

public record RescheduleAppointmentResponse(
        AppointmentResponse cita,
        boolean correoPacienteEnviado,
        boolean correoMedicoEnviado,
        String mensaje
) { }