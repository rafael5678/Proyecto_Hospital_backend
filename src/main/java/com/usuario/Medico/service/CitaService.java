package com.usuario.Medico.service;

import com.usuario.Medico.dto.CitaRequest;
import com.usuario.Medico.dto.CitaResponse;
import com.usuario.Medico.dto.PacientePerfilDTO;
import com.usuario.Medico.dto.ReprogramarCitaRequest;
import com.usuario.Medico.dto.ReprogramarCitaResponse;
import com.usuario.Medico.model.*;
import com.usuario.Medico.repository.CitaRepository;
import com.usuario.Medico.repository.CambioCitaRepository;
import com.usuario.Medico.repository.HorarioRepository;
import com.usuario.Medico.repository.MedicoRepository;
import com.usuario.Medico.repository.PacienteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class CitaService {
    private static final long DURACION_CITA_MINUTOS = 30;

    private final CitaRepository citaRepository;
    private final PacienteRepository pacienteRepository;
    private final MedicoRepository medicoRepository;
    private final PacienteService pacienteService;
    private final CambioCitaRepository cambioCitaRepository;
    private final NotificacionCitaService notificacionCitaService;
    private final HorarioRepository horarioRepository;

    @Transactional
    public CitaResponse agendar(String emailPaciente, CitaRequest req) {
        Paciente paciente = pacienteRepository.findByUsuario_Email(emailPaciente)
                .orElseThrow(() -> new RuntimeException("Perfil de paciente no encontrado"));
        Medico medico = medicoRepository.findByIdForUpdate(req.getMedicoId())
                .orElseThrow(() -> new RuntimeException("Médico no encontrado"));
        validarHorario(medico, req.getFechaHora());

        /* ====== Repriorización automática por gravedad ====== */
        String aviso = null;
        int nuevaPrioridad = req.getTriagePrioridad() != null ? req.getTriagePrioridad() : 5;
        var estadosActivos = List.of(EstadoCita.PENDIENTE, EstadoCita.ACEPTADA);
        LocalDateTime inicioBloque = req.getFechaHora();
        LocalDateTime finBloque = inicioBloque.plusMinutes(DURACION_CITA_MINUTOS);

        List<Cita> solapadas = citaRepository.findCitasSolapadasOrdenPrioridad(
                medico, inicioBloque, finBloque, estadosActivos);

        if (!solapadas.isEmpty()) {
            Cita existente = solapadas.get(0); /* la de menor prioridad */
            int prioridadExistente = existente.getTriagePrioridad() != null ? existente.getTriagePrioridad() : 5;

            if (nuevaPrioridad > prioridadExistente) {
                /* El nuevo paciente es más grave → desplazar al existente */
                LocalDateTime nuevaFechaDesplazado = buscarProximoSlotDisponible(medico, finBloque);
                var fechaAnterior = existente.getFechaHora();
                existente.setFechaHora(nuevaFechaDesplazado);
                citaRepository.save(existente);

                /* Registrar cambio */
                CambioCita cambio = new CambioCita();
                cambio.setCita(existente);
                cambio.setFechaAnterior(fechaAnterior);
                cambio.setFechaNueva(nuevaFechaDesplazado);
                cambio.setMotivo("Repriorización automática: ingresó un paciente con prioridad " +
                        nuevaPrioridad + "/10 (" + nvl(req.getTriageSeveridad()) + "). " +
                        "Su cita fue movida al próximo horario disponible.");
                cambio.setRealizadoPor("SISTEMA_IA");
                cambioCitaRepository.save(cambio);

                /* Notificar al paciente desplazado y al médico */
                var notif = notificacionCitaService.notificarRepriorizacion(
                        existente, fechaAnterior, nuevaPrioridad, nvl(req.getTriageSeveridad()));
                cambio.setCorreoPacienteEnviado(notif.pacienteEnviado());
                cambio.setCorreoMedicoEnviado(notif.medicoEnviado());
                cambioCitaRepository.save(cambio);
                aviso = "Se priorizó tu caso. Otra cita de menor gravedad se reprogramó y se avisó por correo.";
            } else {
                LocalDateTime slotAlternativo = buscarProximoSlotDisponible(medico, finBloque);
                req.setFechaHora(slotAlternativo);
                aviso = "Ese horario ya tenía un paciente de igual o mayor prioridad. Te asignamos el siguiente cupo: "
                        + slotAlternativo + ".";
            }
        }

        Cita cita = Cita.builder()
                .paciente(paciente)
                .medico(medico)
                .fechaHora(req.getFechaHora())
                .motivo(req.getMotivo())
                .notas(req.getNotas())
                .estado(EstadoCita.PENDIENTE)
                /* Campos IA Triage NLP (todos opcionales) */
                .triageSeveridad(req.getTriageSeveridad())
                .triageNivelEsi(req.getTriageNivelEsi())
                .triagePrioridad(req.getTriagePrioridad())
                .triageEspecialidadSugerida(req.getTriageEspecialidadSugerida())
                .triageResumen(req.getTriageResumen())
                .triageSintomas(req.getTriageSintomas())
                .triageDuracion(req.getTriageDuracion())
                .triageAntecedentes(req.getTriageAntecedentes())
                /* Campos IA Dermatología CNN (todos opcionales) */
                .dermatologiaReportaIa(req.getDermatologiaReportaIa())
                .dermatologiaScoreRiesgo(req.getDermatologiaScoreRiesgo())
                .dermatologiaTopDiagnostico(req.getDermatologiaTopDiagnostico())
                .build();
        Cita guardada = citaRepository.save(cita);
        notificacionCitaService.notificarNuevaCita(guardada);
        var dto = CitaMapper.toDto(guardada);
        dto.setAvisoAgenda(aviso);
        return dto;
    }

    /**
     * Busca el próximo bloque de 30 minutos disponible para el médico,
     * empezando desde la fecha dada. Recorre hasta 30 días adelante.
     */
    private LocalDateTime buscarProximoSlotDisponible(Medico medico, LocalDateTime desde) {
        var estadosActivos = List.of(EstadoCita.PENDIENTE, EstadoCita.ACEPTADA);
        var horarios = horarioRepository.findByMedicoAndDisponibleTrue(medico);
        LocalDateTime candidato = desde;
        int intentos = 0;

        while (intentos < 960) { /* ~30 días x 32 slots/día */
            final LocalDateTime finCandidato = candidato.plusMinutes(DURACION_CITA_MINUTOS);
            final LocalDateTime cand = candidato;

            boolean dentroDeHorario = horarios.stream().anyMatch(h ->
                    h.getDiaSemana() == cand.getDayOfWeek().getValue()
                    && !cand.toLocalTime().isBefore(h.getHoraInicio())
                    && !finCandidato.toLocalTime().isAfter(h.getHoraFin()));

            if (dentroDeHorario) {
                boolean ocupado = citaRepository.existeCitaSolapada(
                        medico, candidato.minusMinutes(DURACION_CITA_MINUTOS),
                        finCandidato, estadosActivos, null);
                if (!ocupado && candidato.isAfter(LocalDateTime.now())) {
                    return candidato;
                }
            }

            candidato = candidato.plusMinutes(DURACION_CITA_MINUTOS);
            /* Si pasamos las 21:00, saltar al día siguiente a las 06:00 */
            if (candidato.getHour() >= 21) {
                candidato = candidato.plusDays(1).withHour(6).withMinute(0).withSecond(0);
            }
            intentos++;
        }
        return desde.plusDays(1).withHour(8).withMinute(0); /* fallback */
    }

    private static String nvl(String s) { return s == null || s.isBlank() ? "no especificado" : s; }

    public List<CitaResponse> historialPaciente(String email) {
        Paciente paciente = pacienteRepository.findByUsuario_Email(email)
                .orElseThrow(() -> new RuntimeException("Paciente no encontrado"));
        return citaRepository.findByPacienteOrderByFechaHoraDesc(paciente)
                .stream().map(CitaMapper::toDto).toList();
    }

    public List<CitaResponse> proximasCitas(String email) {
        Paciente paciente = pacienteRepository.findByUsuario_Email(email)
                .orElseThrow(() -> new RuntimeException("Paciente no encontrado"));
        return citaRepository.findByPacienteOrderByFechaHoraDesc(paciente).stream()
                .filter(c -> c.getFechaHora().isAfter(java.time.LocalDateTime.now()))
                .filter(c -> c.getEstado() == EstadoCita.PENDIENTE || c.getEstado() == EstadoCita.ACEPTADA)
                .map(CitaMapper::toDto).toList();
    }

    public List<CitaResponse> citasMedico(String email) {
        Medico medico = medicoRepository.findByUsuario_Email(email)
                .orElseThrow(() -> new RuntimeException("Médico no encontrado"));
        return citaRepository.findByMedicoOrderByFechaHoraAsc(medico)
                .stream().map(CitaMapper::toDto).toList();
    }

    public List<CitaResponse> todasLasCitas() {
        return citaRepository.findAllByOrderByFechaHoraDesc()
                .stream().map(CitaMapper::toDto).toList();
    }

    @Transactional
    public ReprogramarCitaResponse reprogramar(Long id, ReprogramarCitaRequest request, String adminEmail) {
        Cita cita = buscar(id);
        Medico medico = medicoRepository.findByIdForUpdate(cita.getMedico().getId())
                .orElseThrow(() -> new RuntimeException("Médico no encontrado"));
        if (cita.getEstado() != EstadoCita.PENDIENTE && cita.getEstado() != EstadoCita.ACEPTADA) {
            throw new RuntimeException("Solo se pueden reprogramar citas pendientes o aceptadas");
        }
        if (request.getFechaHora().equals(cita.getFechaHora())) {
            throw new RuntimeException("La nueva fecha debe ser diferente a la actual");
        }
        var estadosActivos = List.of(EstadoCita.PENDIENTE, EstadoCita.ACEPTADA);
        validarHorario(medico, request.getFechaHora(), id);

        var fechaAnterior = cita.getFechaHora();
        cita.setFechaHora(request.getFechaHora());
        citaRepository.save(cita);

        CambioCita cambio = new CambioCita();
        cambio.setCita(cita);
        cambio.setFechaAnterior(fechaAnterior);
        cambio.setFechaNueva(cita.getFechaHora());
        cambio.setMotivo(request.getMotivo().trim());
        cambio.setRealizadoPor(adminEmail);
        cambioCitaRepository.save(cambio);

        var notificacion = notificacionCitaService.notificarCambio(cita, fechaAnterior, cambio.getMotivo());
        cambio.setCorreoPacienteEnviado(notificacion.pacienteEnviado());
        cambio.setCorreoMedicoEnviado(notificacion.medicoEnviado());
        cambioCitaRepository.save(cambio);

        String mensaje = notificacion.pacienteEnviado() && notificacion.medicoEnviado()
                ? "Cambio guardado; SMTP aceptó los correos para paciente y médico"
                : "Cambio guardado; no se confirmó el envío a todos los destinatarios. Revisa la configuración SMTP";
        return new ReprogramarCitaResponse(CitaMapper.toDto(cita), notificacion.pacienteEnviado(),
                notificacion.medicoEnviado(), mensaje);
    }

    public CitaResponse cancelar(Long id, String emailPaciente) {
        Cita cita = buscar(id);
        if (!cita.getPaciente().getUsuario().getEmail().equals(emailPaciente)) {
            throw new RuntimeException("No autorizado");
        }
        if (cita.getEstado() == EstadoCita.CANCELADA || cita.getEstado() == EstadoCita.COMPLETADA) {
            throw new RuntimeException("La cita no puede cancelarse");
        }
        cita.setEstado(EstadoCita.CANCELADA);
        return CitaMapper.toDto(citaRepository.save(cita));
    }

    public CitaResponse aceptar(Long id, String emailMedico) {
        Cita cita = buscar(id);
        validarMedico(cita, emailMedico);
        cita.setEstado(EstadoCita.ACEPTADA);
        return CitaMapper.toDto(citaRepository.save(cita));
    }

    public CitaResponse rechazar(Long id, String emailMedico) {
        Cita cita = buscar(id);
        validarMedico(cita, emailMedico);
        cita.setEstado(EstadoCita.RECHAZADA);
        return CitaMapper.toDto(citaRepository.save(cita));
    }

    public PacientePerfilDTO pacienteDeCita(Long citaId, String emailMedico) {
        Cita cita = buscar(citaId);
        validarMedico(cita, emailMedico);
        return pacienteService.toPerfilDto(cita.getPaciente());
    }

    public List<CitaResponse> historialPacienteDeCita(Long citaId, String emailMedico) {
        Cita cita = buscar(citaId);
        validarMedico(cita, emailMedico);
        return citaRepository.findByPacienteOrderByFechaHoraDesc(cita.getPaciente()).stream()
                .filter(anterior -> !anterior.getId().equals(citaId))
                .map(CitaMapper::toDto)
                .toList();
    }

    private void validarMedico(Cita cita, String emailMedico) {
        if (!cita.getMedico().getUsuario().getEmail().equals(emailMedico)) {
            throw new RuntimeException("No autorizado");
        }
    }

    private void validarHorario(Medico medico, LocalDateTime fechaHora) {
        validarHorario(medico, fechaHora, null);
    }

    private void validarHorario(Medico medico, LocalDateTime fechaHora, Long citaExcluida) {
        if (!fechaHora.isAfter(LocalDateTime.now())) {
            throw new RuntimeException("La fecha de la cita debe estar en el futuro");
        }
        LocalDateTime finCita = fechaHora.plusMinutes(DURACION_CITA_MINUTOS);
        boolean dentroDeHorario = horarioRepository.findByMedicoAndDisponibleTrue(medico).stream()
                .anyMatch(horario -> horario.getDiaSemana() == fechaHora.getDayOfWeek().getValue()
                        && !fechaHora.toLocalTime().isBefore(horario.getHoraInicio())
                        && !finCita.toLocalTime().isAfter(horario.getHoraFin()));
        if (!dentroDeHorario) {
            throw new RuntimeException("La hora debe estar dentro de un horario disponible del médico (citas de 30 minutos)");
        }
        var estadosActivos = List.of(EstadoCita.PENDIENTE, EstadoCita.ACEPTADA);
        if (citaRepository.existeCitaSolapada(medico, fechaHora.minusMinutes(DURACION_CITA_MINUTOS),
            finCita, estadosActivos, citaExcluida)) {
            throw new RuntimeException("El médico ya tiene una cita activa en ese bloque de 30 minutos");
        }
    }

    private Cita buscar(Long id) {
        return citaRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Cita no encontrada"));
    }
}
