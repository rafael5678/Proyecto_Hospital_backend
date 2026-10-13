package com.hospy.service;

import com.hospy.dto.AppointmentRequest;
import com.hospy.dto.AppointmentResponse;
import com.hospy.dto.SlotResponse;
import com.hospy.dto.PatientProfileDto;
import com.hospy.dto.RescheduleAppointmentRequest;
import com.hospy.dto.RescheduleAppointmentResponse;
import com.hospy.model.*;
import com.hospy.repository.AppointmentRepository;
import com.hospy.repository.AppointmentChangeRepository;
import com.hospy.repository.ScheduleRepository;
import com.hospy.repository.DoctorRepository;
import com.hospy.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AppointmentService {
    private static final long DURACION_CITA_MINUTOS = 30;

    private final AppointmentRepository citaRepository;
    private final PatientRepository pacienteRepository;
    private final DoctorRepository medicoRepository;
    private final PatientService pacienteService;
    private final AppointmentChangeRepository cambioCitaRepository;
    private final AppointmentNotificationService notificacionCitaService;
    private final ScheduleRepository horarioRepository;
    private final ScheduleService horarioService;

    @Transactional
    public AppointmentResponse agendar(String emailPaciente, AppointmentRequest req) {
        Patient paciente = pacienteRepository.findByUsuario_Email(emailPaciente)
                .orElseThrow(() -> new RuntimeException("Perfil de paciente no encontrado"));
        Doctor medico = medicoRepository.findByIdForUpdate(req.getMedicoId())
                .orElseThrow(() -> new RuntimeException("Médico no encontrado"));
        horarioService.asegurarHorarioLaboral(medico);

        /* ====== Repriorización automática por gravedad ====== */
        String aviso = null;
        int nuevaPrioridad = req.getTriagePrioridad() != null ? req.getTriagePrioridad() : 5;
        LocalDateTime desde = alinearMediaHora(LocalDateTime.now().plusMinutes(5));
        if (req.getFechaHora() == null || !estaEnHorarioLaboral(medico, req.getFechaHora())
                || !req.getFechaHora().isAfter(LocalDateTime.now())) {
            if (nuevaPrioridad >= 8) {
                req.setFechaHora(primerSlotLaboral(medico, desde));
                aviso = "Por la gravedad, la aplicación tomó el cupo más próximo del médico.";
            } else {
                req.setFechaHora(buscarProximoSlotDisponible(medico, desde));
                aviso = "La aplicación asignó el primer cupo libre del médico.";
            }
        }
        validarHorarioPermitirSolape(medico, req.getFechaHora());
        var estadosActivos = List.of(AppointmentStatus.PENDIENTE, AppointmentStatus.ACEPTADA);
        LocalDateTime inicioBloque = req.getFechaHora();
        LocalDateTime finBloque = inicioBloque.plusMinutes(DURACION_CITA_MINUTOS);

        List<Appointment> solapadas = citaRepository.findCitasSolapadasOrdenPrioridad(
                medico, inicioBloque, finBloque, estadosActivos);

        if (!solapadas.isEmpty()) {
            Appointment existente = solapadas.get(0); /* la de menor prioridad */
            int prioridadExistente = existente.getTriagePrioridad() != null ? existente.getTriagePrioridad() : 5;

            if (nuevaPrioridad > prioridadExistente) {
                /* El nuevo paciente es más grave → desplazar al existente */
                LocalDateTime nuevaFechaDesplazado = buscarProximoSlotDisponible(medico, finBloque);
                var fechaAnterior = existente.getFechaHora();
                existente.setFechaHora(nuevaFechaDesplazado);
                citaRepository.save(existente);

                /* Registrar cambio */
                AppointmentChange cambio = new AppointmentChange();
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

        Appointment cita = Appointment.builder()
                .paciente(paciente)
                .medico(medico)
                .fechaHora(req.getFechaHora())
                .motivo(req.getMotivo())
                .notas(req.getNotas())
                .estado(AppointmentStatus.PENDIENTE)
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
        Appointment guardada = citaRepository.save(cita);
        notificacionCitaService.notificarNuevaCita(guardada);
        var dto = AppointmentMapper.toDto(guardada);
        dto.setAvisoAgenda(aviso);
        return dto;
    }

    public List<SlotResponse> listarCupos(Long medicoId, int limite) {
        Doctor medico = medicoRepository.findById(medicoId)
                .orElseThrow(() -> new RuntimeException("Médico no encontrado"));
        int max = Math.min(Math.max(limite, 1), 16);
        List<SlotResponse> cupos = new ArrayList<>();
        for (LocalDateTime slot : slotsLibres(medico, LocalDateTime.now().plusMinutes(5), max)) {
            cupos.add(SlotResponse.builder()
                    .fechaHora(slot)
                    .dia(ScheduleService.nombreDia(slot.getDayOfWeek().getValue()))
                    .etiqueta(ScheduleService.nombreDia(slot.getDayOfWeek().getValue())
                            + " " + slot.toLocalDate() + " · " + slot.toLocalTime().withSecond(0).withNano(0))
                    .ocupadoMenorPrioridad(false)
                    .build());
        }
        return cupos;
    }

    private LocalDateTime buscarProximoSlotDisponible(Doctor medico, LocalDateTime desde) {
        List<LocalDateTime> slots = slotsLibres(medico, desde, 1);
        if (slots.isEmpty()) {
            throw new RuntimeException("El médico no tiene cupos libres en las próximas semanas.");
        }
        return slots.get(0);
    }

    /** Una sola lectura de horarios y citas; recorre ventanas laborales, no cada media hora del mes. */
    private List<LocalDateTime> slotsLibres(Doctor medico, LocalDateTime desde, int max) {
        horarioService.asegurarHorarioLaboral(medico);
        var horarios = horarioRepository.findByMedicoAndDisponibleTrue(medico);
        var estados = List.of(AppointmentStatus.PENDIENTE, AppointmentStatus.ACEPTADA);
        Set<LocalDateTime> ocupadas = new HashSet<>();
        for (Appointment c : citaRepository.findByMedicoOrderByFechaHoraAsc(medico)) {
            if (estados.contains(c.getEstado()) && c.getFechaHora() != null) {
                ocupadas.add(c.getFechaHora().withSecond(0).withNano(0));
            }
        }
        List<LocalDateTime> out = new ArrayList<>();
        LocalDateTime piso = alinearMediaHora(desde);
        LocalDate dia = piso.toLocalDate();
        for (int d = 0; d < 21 && out.size() < max; d++) {
            LocalDate fecha = dia.plusDays(d);
            int dow = fecha.getDayOfWeek().getValue();
            for (var h : horarios) {
                if (h.getDiaSemana() != dow) continue;
                LocalDateTime slot = fecha.atTime(h.getHoraInicio()).withSecond(0).withNano(0);
                if (slot.isBefore(piso)) slot = piso.toLocalDate().equals(fecha) ? piso : slot;
                if (slot.toLocalTime().isBefore(h.getHoraInicio())) slot = fecha.atTime(h.getHoraInicio());
                slot = alinearMediaHora(slot);
                while (out.size() < max
                        && slot.toLocalDate().equals(fecha)
                        && !slot.toLocalTime().isBefore(h.getHoraInicio())
                        && !slot.plusMinutes(DURACION_CITA_MINUTOS).toLocalTime().isAfter(h.getHoraFin())) {
                    if (slot.isAfter(LocalDateTime.now()) && !ocupadas.contains(slot)) {
                        out.add(slot);
                    }
                    slot = slot.plusMinutes(DURACION_CITA_MINUTOS);
                }
            }
        }
        return out;
    }

    private LocalDateTime primerSlotLaboral(Doctor medico, LocalDateTime desde) {
        LocalDateTime candidato = alinearMediaHora(desde);
        for (int i = 0; i < 960; i++) {
            if (estaEnHorarioLaboral(medico, candidato)) return candidato;
            candidato = candidato.plusMinutes(DURACION_CITA_MINUTOS);
            if (candidato.getHour() >= 21) {
                candidato = candidato.plusDays(1).withHour(6).withMinute(0).withSecond(0).withNano(0);
            }
        }
        return buscarProximoSlotDisponible(medico, desde);
    }

    private static LocalDateTime alinearMediaHora(LocalDateTime t) {
        LocalDateTime n = t.withSecond(0).withNano(0);
        int m = n.getMinute();
        if (m == 0 || m == 30) return n;
        if (m < 30) return n.withMinute(30);
        return n.plusHours(1).withMinute(0);
    }

    private static String nvl(String s) { return s == null || s.isBlank() ? "no especificado" : s; }

    public List<AppointmentResponse> historialPaciente(String email) {
        Patient paciente = pacienteRepository.findByUsuario_Email(email)
                .orElseThrow(() -> new RuntimeException("Patient no encontrado"));
        return citaRepository.findByPacienteOrderByFechaHoraDesc(paciente)
                .stream().map(AppointmentMapper::toDto).toList();
    }

    public List<AppointmentResponse> proximasCitas(String email) {
        Patient paciente = pacienteRepository.findByUsuario_Email(email)
                .orElseThrow(() -> new RuntimeException("Patient no encontrado"));
        return citaRepository.findByPacienteOrderByFechaHoraDesc(paciente).stream()
                .filter(c -> c.getFechaHora().isAfter(java.time.LocalDateTime.now()))
                .filter(c -> c.getEstado() == AppointmentStatus.PENDIENTE || c.getEstado() == AppointmentStatus.ACEPTADA)
                .map(AppointmentMapper::toDto).toList();
    }

    public List<AppointmentResponse> citasMedico(String email) {
        Doctor medico = medicoRepository.findByUsuario_Email(email)
                .orElseThrow(() -> new RuntimeException("Médico no encontrado"));
        return citaRepository.findByMedicoOrderByFechaHoraAsc(medico)
                .stream().map(AppointmentMapper::toDto).toList();
    }

    public List<AppointmentResponse> todasLasCitas() {
        return citaRepository.findAllByOrderByFechaHoraDesc()
                .stream().map(AppointmentMapper::toDto).toList();
    }

    @Transactional
    public RescheduleAppointmentResponse reprogramar(Long id, RescheduleAppointmentRequest request, String adminEmail) {
        Appointment cita = buscar(id);
        Doctor medico = medicoRepository.findByIdForUpdate(cita.getMedico().getId())
                .orElseThrow(() -> new RuntimeException("Médico no encontrado"));
        if (cita.getEstado() != AppointmentStatus.PENDIENTE && cita.getEstado() != AppointmentStatus.ACEPTADA) {
            throw new RuntimeException("Solo se pueden reprogramar citas pendientes o aceptadas");
        }
        if (request.getFechaHora().equals(cita.getFechaHora())) {
            throw new RuntimeException("La nueva fecha debe ser diferente a la actual");
        }
        var estadosActivos = List.of(AppointmentStatus.PENDIENTE, AppointmentStatus.ACEPTADA);
        validarHorario(medico, request.getFechaHora(), id);

        var fechaAnterior = cita.getFechaHora();
        cita.setFechaHora(request.getFechaHora());
        citaRepository.save(cita);

        AppointmentChange cambio = new AppointmentChange();
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
        return new RescheduleAppointmentResponse(AppointmentMapper.toDto(cita), notificacion.pacienteEnviado(),
                notificacion.medicoEnviado(), mensaje);
    }

    @Transactional
    public AppointmentResponse cancelar(Long id, String emailPaciente) {
        Appointment cita = buscar(id);
        if (!cita.getPaciente().getUsuario().getEmail().equals(emailPaciente)) {
            throw new RuntimeException("No autorizado");
        }
        if (cita.getEstado() == AppointmentStatus.CANCELADA || cita.getEstado() == AppointmentStatus.COMPLETADA) {
            throw new RuntimeException("La cita no puede cancelarse");
        }
        cita.setEstado(AppointmentStatus.CANCELADA);
        Appointment guardada = citaRepository.save(cita);
        notificacionCitaService.notificarCitaCancelada(guardada);
        return AppointmentMapper.toDto(guardada);
    }

    @Transactional
    public AppointmentResponse aceptar(Long id, String emailMedico) {
        Appointment cita = buscar(id);
        validarMedico(cita, emailMedico);
        cita.setEstado(AppointmentStatus.ACEPTADA);
        Appointment guardada = citaRepository.save(cita);
        notificacionCitaService.notificarCitaAceptada(guardada);
        return AppointmentMapper.toDto(guardada);
    }

    @Transactional
    public AppointmentResponse rechazar(Long id, String emailMedico) {
        Appointment cita = buscar(id);
        validarMedico(cita, emailMedico);
        cita.setEstado(AppointmentStatus.RECHAZADA);
        Appointment guardada = citaRepository.save(cita);
        notificacionCitaService.notificarCitaRechazada(guardada);
        return AppointmentMapper.toDto(guardada);
    }

    public PatientProfileDto pacienteDeCita(Long citaId, String emailMedico) {
        Appointment cita = buscar(citaId);
        validarMedico(cita, emailMedico);
        return pacienteService.toPerfilDto(cita.getPaciente());
    }

    public List<AppointmentResponse> historialPacienteDeCita(Long citaId, String emailMedico) {
        Appointment cita = buscar(citaId);
        validarMedico(cita, emailMedico);
        return citaRepository.findByPacienteOrderByFechaHoraDesc(cita.getPaciente()).stream()
                .filter(anterior -> !anterior.getId().equals(citaId))
                .map(AppointmentMapper::toDto)
                .toList();
    }

    private void validarMedico(Appointment cita, String emailMedico) {
        if (!cita.getMedico().getUsuario().getEmail().equals(emailMedico)) {
            throw new RuntimeException("No autorizado");
        }
    }

    private void validarHorario(Doctor medico, LocalDateTime fechaHora) {
        validarHorario(medico, fechaHora, null);
    }

    private void validarHorarioPermitirSolape(Doctor medico, LocalDateTime fechaHora) {
        if (fechaHora == null || !fechaHora.isAfter(LocalDateTime.now())) {
            throw new RuntimeException("No hay un cupo futuro para este médico.");
        }
        if (!estaEnHorarioLaboral(medico, fechaHora)) {
            throw new RuntimeException("Ese cupo no está en el horario laboral del médico.");
        }
    }

    private boolean estaEnHorarioLaboral(Doctor medico, LocalDateTime fechaHora) {
        if (fechaHora == null) return false;
        LocalDateTime finCita = fechaHora.plusMinutes(DURACION_CITA_MINUTOS);
        return horarioRepository.findByMedicoAndDisponibleTrue(medico).stream()
                .anyMatch(horario -> horario.getDiaSemana() == fechaHora.getDayOfWeek().getValue()
                        && !fechaHora.toLocalTime().isBefore(horario.getHoraInicio())
                        && !finCita.toLocalTime().isAfter(horario.getHoraFin()));
    }

    private void validarHorario(Doctor medico, LocalDateTime fechaHora, Long citaExcluida) {
        validarHorarioPermitirSolape(medico, fechaHora);
        LocalDateTime finCita = fechaHora.plusMinutes(DURACION_CITA_MINUTOS);
        var estadosActivos = List.of(AppointmentStatus.PENDIENTE, AppointmentStatus.ACEPTADA);
        if (citaRepository.existeCitaSolapada(medico, fechaHora.minusMinutes(DURACION_CITA_MINUTOS),
            finCita, estadosActivos, citaExcluida)) {
            throw new RuntimeException("El médico ya tiene una cita activa en ese bloque de 30 minutos");
        }
    }

    private Appointment buscar(Long id) {
        return citaRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Appointment no encontrada"));
    }
}
