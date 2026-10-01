package com.usuario.Medico.service;

import com.usuario.Medico.dto.CitaResponse;
import com.usuario.Medico.model.Cita;

public final class CitaMapper {

    private CitaMapper() {}

    public static CitaResponse toDto(Cita c) {
        return CitaResponse.builder()
                .id(c.getId())
                .pacienteId(c.getPaciente().getId())
                .pacienteNombre(c.getPaciente().getUsuario().getNombre())
                .pacienteDocumento(c.getPaciente().getDocumento())
                .medicoId(c.getMedico().getId())
                .medicoNombre(c.getMedico().getUsuario().getNombre())
                .medicoEspecialidad(c.getMedico().getEspecialidad())
                .fechaHora(c.getFechaHora())
                .estado(c.getEstado().name())
                .motivo(c.getMotivo())
                .notas(c.getNotas())
                .triageSeveridad(c.getTriageSeveridad())
                .triageNivelEsi(c.getTriageNivelEsi())
                .triagePrioridad(c.getTriagePrioridad())
                .triageEspecialidadSugerida(c.getTriageEspecialidadSugerida())
                .triageResumen(c.getTriageResumen())
                .triageSintomas(c.getTriageSintomas())
                .triageDuracion(c.getTriageDuracion())
                .triageAntecedentes(c.getTriageAntecedentes())
                .dermatologiaReportaIa(c.getDermatologiaReportaIa())
                .dermatologiaScoreRiesgo(c.getDermatologiaScoreRiesgo())
                .dermatologiaTopDiagnostico(c.getDermatologiaTopDiagnostico())
                .avisoAgenda(null)
                .build();
    }
}
