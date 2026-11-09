package com.hospy.dto;

/** JSON result after a visit is moved. */
public record RescheduleAppointmentResponse(
        AppointmentResponse cita,
        boolean correoPacienteEnviado,
        boolean correoMedicoEnviado,
        String mensaje
) { }