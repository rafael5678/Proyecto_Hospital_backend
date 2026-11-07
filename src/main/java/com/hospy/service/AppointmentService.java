package com.hospy.service;

import com.hospy.dto.AppointmentRequest;
import com.hospy.dto.AppointmentResponse;
import com.hospy.dto.PrescriptionRequest;
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
    public AppointmentResponse book(String emailPaciente, AppointmentRequest req) {
        Patient paciente = pacienteRepository.findByUsuario_Email(emailPaciente)
                .orElseThrow(() -> new RuntimeException("Perfil de paciente no encontrado"));
        Doctor medico = medicoRepository.findByIdForUpdate(req.getMedicoId())
                .orElseThrow(() -> new RuntimeException("Médico no encontrado"));
        horarioService.ensureWorkSchedule(medico);

        /* ====== Repriorización automática por gravedad ====== */
        String aviso = null;
        int nuevaPrioridad = req.getTriagePrioridad() != null ? req.getTriagePrioridad() : 5;
        LocalDateTime desde = alignToHalfHour(ColombiaTime.now().plusMinutes(5));
        if (req.getFechaHora() == null || !isWithinWorkHours(medico, req.getFechaHora())
                || !req.getFechaHora().isAfter(ColombiaTime.now())) {
            if (nuevaPrioridad >= 8) {
                req.setFechaHora(firstWorkSlot(medico, desde));
                aviso = "Por la gravedad, la aplicación tomó el cupo más próximo del médico.";
            } else {
                req.setFechaHora(findNextOpenSlot(medico, desde));
                aviso = "La aplicación asignó el primer cupo libre del médico.";
            }
        }
        requireScheduleAllowingOverlap(medico, req.getFechaHora());
        var estadosActivos = List.of(AppointmentStatus.PENDIENTE, AppointmentStatus.ACEPTADA);
        LocalDateTime inicioBloque = req.getFechaHora();
        LocalDateTime finBloque = inicioBloque.plusMinutes(DURACION_CITA_MINUTOS);

        List<Appointment> solapadas = citaRepository.findOverlappingByPriority(
                medico, inicioBloque, finBloque, estadosActivos);

        if (!solapadas.isEmpty()) {
            Appointment existente = solapadas.get(0); /* la de menor prioridad */
            int prioridadExistente = existente.getTriagePrioridad() != null ? existente.getTriagePrioridad() : 5;

            if (nuevaPrioridad > prioridadExistente) {
                /* El nuevo paciente es más grave → desplazar al existente */
                LocalDateTime nuevaFechaDesplazado = findNextOpenSlot(medico, finBloque);
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
                var notif = notificacionCitaService.notifyReprioritization(
                        existente, fechaAnterior, nuevaPrioridad, nvl(req.getTriageSeveridad()));
                cambio.setCorreoPacienteEnviado(notif.patientSent());
                cambio.setCorreoMedicoEnviado(notif.doctorSent());
                cambioCitaRepository.save(cambio);
                aviso = "Se priorizó tu caso. Otra cita de menor gravedad se reprogramó y se avisó por correo.";
            } else {
                LocalDateTime slotAlternativo = findNextOpenSlot(medico, finBloque);
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
        notificacionCitaService.notifyNewAppointment(guardada);
        var dto = AppointmentMapper.toDto(guardada);
        dto.setAvisoAgenda(aviso);
        return dto;
    }

    public List<SlotResponse> listSlots(Long medicoId, int limite) {
        Doctor medico = medicoRepository.findById(medicoId)
                .orElseThrow(() -> new RuntimeException("Médico no encontrado"));
        int max = Math.min(Math.max(limite, 1), 16);
        List<SlotResponse> slots = new ArrayList<>();
        for (LocalDateTime slot : openSlots(medico, ColombiaTime.now().plusMinutes(5), max)) {
            slots.add(SlotResponse.builder()
                    .fechaHora(slot)
                    .dia(ScheduleService.dayName(slot.getDayOfWeek().getValue()))
                    .etiqueta(ScheduleService.dayName(slot.getDayOfWeek().getValue())
                            + " " + slot.toLocalDate() + " · " + slot.toLocalTime().withSecond(0).withNano(0))
                    .ocupadoMenorPrioridad(false)
                    .build());
        }
        return slots;
    }

    private LocalDateTime findNextOpenSlot(Doctor medico, LocalDateTime desde) {
        List<LocalDateTime> slots = openSlots(medico, desde, 1);
        if (slots.isEmpty()) {
            throw new RuntimeException("El médico no tiene cupos libres en las próximas semanas.");
        }
        return slots.get(0);
    }

    /** Una sola lectura de horarios y citas; recorre ventanas laborales, no cada media hora del mes. */
    private List<LocalDateTime> openSlots(Doctor medico, LocalDateTime desde, int max) {
        horarioService.ensureWorkSchedule(medico);
        var schedules = horarioRepository.findByMedicoAndDisponibleTrue(medico);
        var estados = List.of(AppointmentStatus.PENDIENTE, AppointmentStatus.ACEPTADA);
        Set<LocalDateTime> ocupadas = new HashSet<>();
        for (Appointment c : citaRepository.findByMedicoOrderByFechaHoraAsc(medico)) {
            if (estados.contains(c.getEstado()) && c.getFechaHora() != null) {
                ocupadas.add(c.getFechaHora().withSecond(0).withNano(0));
            }
        }
        List<LocalDateTime> out = new ArrayList<>();
        LocalDateTime piso = alignToHalfHour(desde);
        LocalDate dia = piso.toLocalDate();
        for (int d = 0; d < 21 && out.size() < max; d++) {
            LocalDate fecha = dia.plusDays(d);
            int dow = fecha.getDayOfWeek().getValue();
            for (var h : schedules) {
                if (h.getDiaSemana() != dow) continue;
                LocalDateTime slot = fecha.atTime(h.getHoraInicio()).withSecond(0).withNano(0);
                if (slot.isBefore(piso)) slot = piso.toLocalDate().equals(fecha) ? piso : slot;
                if (slot.toLocalTime().isBefore(h.getHoraInicio())) slot = fecha.atTime(h.getHoraInicio());
                slot = alignToHalfHour(slot);
                while (out.size() < max
                        && slot.toLocalDate().equals(fecha)
                        && !slot.toLocalTime().isBefore(h.getHoraInicio())
                        && !slot.plusMinutes(DURACION_CITA_MINUTOS).toLocalTime().isAfter(h.getHoraFin())) {
                    if (slot.isAfter(ColombiaTime.now()) && !ocupadas.contains(slot)) {
                        out.add(slot);
                    }
                    slot = slot.plusMinutes(DURACION_CITA_MINUTOS);
                }
            }
        }
        return out;
    }

    private LocalDateTime firstWorkSlot(Doctor medico, LocalDateTime desde) {
        LocalDateTime candidato = alignToHalfHour(desde);
        for (int i = 0; i < 960; i++) {
            if (isWithinWorkHours(medico, candidato)) return candidato;
            candidato = candidato.plusMinutes(DURACION_CITA_MINUTOS);
            if (candidato.getHour() >= 21) {
                candidato = candidato.plusDays(1).withHour(6).withMinute(0).withSecond(0).withNano(0);
            }
        }
        return findNextOpenSlot(medico, desde);
    }

    private static LocalDateTime alignToHalfHour(LocalDateTime t) {
        LocalDateTime n = t.withSecond(0).withNano(0);
        int m = n.getMinute();
        if (m == 0 || m == 30) return n;
        if (m < 30) return n.withMinute(30);
        return n.plusHours(1).withMinute(0);
    }

    private static String nvl(String s) { return s == null || s.isBlank() ? "no especificado" : s; }

    public List<AppointmentResponse> patientHistory(String email) {
        Patient paciente = pacienteRepository.findByUsuario_Email(email)
                .orElseThrow(() -> new RuntimeException("Patient no encontrado"));
        return citaRepository.findByPacienteOrderByFechaHoraDesc(paciente)
                .stream().map(AppointmentMapper::toDto).toList();
    }

    public List<AppointmentResponse> proximasCitas(String email) {
        Patient paciente = pacienteRepository.findByUsuario_Email(email)
                .orElseThrow(() -> new RuntimeException("Patient no encontrado"));
        return citaRepository.findByPacienteOrderByFechaHoraDesc(paciente).stream()
                .filter(c -> c.getFechaHora().isAfter(ColombiaTime.now()))
                .filter(c -> c.getEstado() == AppointmentStatus.PENDIENTE || c.getEstado() == AppointmentStatus.ACEPTADA)
                .map(AppointmentMapper::toDto).toList();
    }

    public List<AppointmentResponse> appointmentsForDoctor(String email) {
        Doctor medico = medicoRepository.findByUsuario_Email(email)
                .orElseThrow(() -> new RuntimeException("Médico no encontrado"));
        return citaRepository.findByMedicoOrderByFechaHoraAsc(medico)
                .stream().map(AppointmentMapper::toDto).toList();
    }

    public List<AppointmentResponse> allAppointments() {
        return citaRepository.findAllByOrderByFechaHoraDesc()
                .stream().map(AppointmentMapper::toDto).toList();
    }

    @Transactional
    public RescheduleAppointmentResponse reschedule(Long id, RescheduleAppointmentRequest request, String adminEmail) {
        Appointment cita = findStored(id);
        Doctor medico = medicoRepository.findByIdForUpdate(cita.getMedico().getId())
                .orElseThrow(() -> new RuntimeException("Médico no encontrado"));
        if (cita.getEstado() != AppointmentStatus.PENDIENTE && cita.getEstado() != AppointmentStatus.ACEPTADA) {
            throw new RuntimeException("Solo se pueden reprogramar citas pendientes o aceptadas");
        }
        if (request.getFechaHora().equals(cita.getFechaHora())) {
            throw new RuntimeException("La nueva fecha debe ser diferente a la actual");
        }
        var estadosActivos = List.of(AppointmentStatus.PENDIENTE, AppointmentStatus.ACEPTADA);
        requireSchedule(medico, request.getFechaHora(), id);

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

        var notificacion = notificacionCitaService.notifyScheduleChange(cita, fechaAnterior, cambio.getMotivo());
        cambio.setCorreoPacienteEnviado(notificacion.patientSent());
        cambio.setCorreoMedicoEnviado(notificacion.doctorSent());
        cambioCitaRepository.save(cambio);

        String mensaje = notificacion.patientSent() && notificacion.doctorSent()
                ? "Cambio guardado; SMTP aceptó los correos para paciente y médico"
                : "Cambio guardado; no se confirmó el envío a todos los destinatarios. Revisa la configuración SMTP";
        return new RescheduleAppointmentResponse(AppointmentMapper.toDto(cita), notificacion.patientSent(),
                notificacion.doctorSent(), mensaje);
    }

    @Transactional
    public AppointmentResponse cancel(Long id, String emailPaciente) {
        Appointment cita = findStored(id);
        if (!cita.getPaciente().getUsuario().getEmail().equals(emailPaciente)) {
            throw new RuntimeException("No autorizado");
        }
        if (cita.getEstado() == AppointmentStatus.CANCELADA || cita.getEstado() == AppointmentStatus.COMPLETADA) {
            throw new RuntimeException("La cita no puede cancelarse");
        }
        cita.setEstado(AppointmentStatus.CANCELADA);
        Appointment guardada = citaRepository.save(cita);
        notificacionCitaService.notifyAppointmentCancelled(guardada);
        return AppointmentMapper.toDto(guardada);
    }

    @Transactional
    public AppointmentResponse accept(Long id, String emailMedico) {
        Appointment cita = findStored(id);
        requireDoctor(cita, emailMedico);
        cita.setEstado(AppointmentStatus.ACEPTADA);
        Appointment guardada = citaRepository.save(cita);
        notificacionCitaService.notifyAppointmentAccepted(guardada);
        return AppointmentMapper.toDto(guardada);
    }

    @Transactional
    public AppointmentResponse reject(Long id, String emailMedico) {
        Appointment cita = findStored(id);
        requireDoctor(cita, emailMedico);
        cita.setEstado(AppointmentStatus.RECHAZADA);
        Appointment guardada = citaRepository.save(cita);
        notificacionCitaService.notifyAppointmentRejected(guardada);
        return AppointmentMapper.toDto(guardada);
    }

    public PatientProfileDto patientForAppointment(Long citaId, String emailMedico) {
        Appointment cita = findStored(citaId);
        requireDoctor(cita, emailMedico);
        return pacienteService.toProfileDto(cita.getPaciente());
    }

    @Transactional
    public boolean sendPrescription(Long citaId, String emailMedico, PrescriptionRequest req) {
        Appointment cita = findStored(citaId);
        requireDoctor(cita, emailMedico);
        String medicamentos = req == null || req.getMedicamentos() == null ? "" : req.getMedicamentos().trim();
        String cuidados = req == null || req.getCuidados() == null ? "" : req.getCuidados().trim();
        String resumen = req == null || req.getResumen() == null ? "" : req.getResumen().trim();
        if (medicamentos.isBlank() && cuidados.isBlank()) {
            throw new RuntimeException("Elige un medicamento o escribe qué debe hacer el paciente.");
        }
        cita.setEstado(AppointmentStatus.COMPLETADA);
        cita.setConcluidaEn(ColombiaTime.now());
        cita.setReceta(medicamentos);
        citaRepository.save(cita);
        String medico = cita.getMedico().getUsuario().getNombre();
        StringBuilder mensaje = new StringBuilder();
        mensaje.append(medico).append(" te dejó estas indicaciones de la consulta.");
        if (!medicamentos.isBlank()) mensaje.append("\n\nMedicamentos: ").append(medicamentos);
        if (!cuidados.isBlank()) mensaje.append("\n\nQué hacer: ").append(cuidados);
        if (!resumen.isBlank()) mensaje.append("\n\nQué vimos: ").append(resumen);
        return notificacionCitaService.notifyPrescription(cita, mensaje.toString());
    }

    public List<AppointmentResponse> historyForDoctorVisit(Long citaId, String emailMedico) {
        Appointment cita = findStored(citaId);
        requireDoctor(cita, emailMedico);
        Long medicoId = cita.getMedico().getId();
        return citaRepository.findByPacienteOrderByFechaHoraDesc(cita.getPaciente()).stream()
                .filter(anterior -> anterior.getMedico() != null && medicoId.equals(anterior.getMedico().getId()))
                .filter(anterior -> !anterior.getId().equals(citaId))
                .map(AppointmentMapper::toDto)
                .toList();
    }

    private void requireDoctor(Appointment cita, String emailMedico) {
        if (!cita.getMedico().getUsuario().getEmail().equals(emailMedico)) {
            throw new RuntimeException("No autorizado");
        }
    }

    private void requireSchedule(Doctor medico, LocalDateTime fechaHora) {
        requireSchedule(medico, fechaHora, null);
    }

    private void requireScheduleAllowingOverlap(Doctor medico, LocalDateTime fechaHora) {
        if (fechaHora == null || !fechaHora.isAfter(ColombiaTime.now())) {
            throw new RuntimeException("No hay un cupo futuro para este médico.");
        }
        if (!isWithinWorkHours(medico, fechaHora)) {
            throw new RuntimeException("Ese cupo no está en el horario laboral del médico.");
        }
    }

    private boolean isWithinWorkHours(Doctor medico, LocalDateTime fechaHora) {
        if (fechaHora == null) return false;
        LocalDateTime finCita = fechaHora.plusMinutes(DURACION_CITA_MINUTOS);
        return horarioRepository.findByMedicoAndDisponibleTrue(medico).stream()
                .anyMatch(horario -> horario.getDiaSemana() == fechaHora.getDayOfWeek().getValue()
                        && !fechaHora.toLocalTime().isBefore(horario.getHoraInicio())
                        && !finCita.toLocalTime().isAfter(horario.getHoraFin()));
    }

    private void requireSchedule(Doctor medico, LocalDateTime fechaHora, Long citaExcluida) {
        requireScheduleAllowingOverlap(medico, fechaHora);
        LocalDateTime finCita = fechaHora.plusMinutes(DURACION_CITA_MINUTOS);
        var estadosActivos = List.of(AppointmentStatus.PENDIENTE, AppointmentStatus.ACEPTADA);
        if (citaRepository.existsOverlappingAppointment(medico, fechaHora.minusMinutes(DURACION_CITA_MINUTOS),
            finCita, estadosActivos, citaExcluida)) {
            throw new RuntimeException("El médico ya tiene una cita activa en ese bloque de 30 minutos");
        }
    }

    private Appointment findStored(Long id) {
        return citaRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Appointment no encontrada"));
    }
}
