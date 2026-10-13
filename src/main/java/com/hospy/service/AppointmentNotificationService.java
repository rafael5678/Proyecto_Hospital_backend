package com.hospy.service;

import com.hospy.model.Appointment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
public class AppointmentNotificationService {
    private static final DateTimeFormatter FORMATO = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final ObjectProvider<JavaMailSender> senderProvider;

    @Value("${spring.mail.host:}")
    private String mailHost;

    @Value("${app.mail.from:noreply@hospy.local}")
    private String from;

    private final NotificationInboxService inbox;

    public AppointmentNotificationService(ObjectProvider<JavaMailSender> senderProvider,
                                   NotificationInboxService inbox) {
        this.senderProvider = senderProvider;
        this.inbox = inbox;
    }

    public ResultadoNotificacion notificarCambio(Appointment cita, LocalDateTime fechaAnterior, String motivo) {
        String asunto = "Cambio en tu cita médica";
        String textoPaciente = "Hola " + cita.getPaciente().getUsuario().getNombre() + ",\n\n" +
                "La cita con " + cita.getMedico().getUsuario().getNombre() +
                " cambió de " + FORMATO.format(fechaAnterior) + " a " + FORMATO.format(cita.getFechaHora()) +
                ".\nMotivo: " + motivo + "\n\nSi necesitas ayuda, contacta al centro médico.";
        String textoMedico = "Hola " + cita.getMedico().getUsuario().getNombre() + ",\n\n" +
                "La cita de " + cita.getPaciente().getUsuario().getNombre() +
                " cambió de " + FORMATO.format(fechaAnterior) + " a " + FORMATO.format(cita.getFechaHora()) +
                ".\nMotivo administrativo: " + motivo;

        inbox.crearParaEmail(cita.getPaciente().getUsuario().getEmail(), asunto, textoPaciente,
                "CITA_CAMBIO", cita.getId());
        inbox.crearParaEmail(cita.getMedico().getUsuario().getEmail(), "Cambio en una cita", textoMedico,
                "CITA_CAMBIO", cita.getId());
        boolean paciente = enviar(cita.getPaciente().getUsuario().getEmail(), asunto, textoPaciente);
        boolean medico = enviar(cita.getMedico().getUsuario().getEmail(), asunto, textoMedico);
        return new ResultadoNotificacion(paciente, medico);
    }

    public boolean notificarNuevaCita(Appointment cita) {
        String asunto = "Nueva cita — resumen IA para revisión";
        String triage = cita.getTriageResumen() == null ? "Sin triage" :
                "Severidad " + nvl(cita.getTriageSeveridad()) +
                ", prioridad " + (cita.getTriagePrioridad() == null ? "-" : cita.getTriagePrioridad()) +
                "/10, ESI " + (cita.getTriageNivelEsi() == null ? "-" : cita.getTriageNivelEsi()) +
                ". " + cita.getTriageResumen();
        String piel = cita.getDermatologiaTopDiagnostico() == null ? "" :
                "\nPiel: " + cita.getDermatologiaTopDiagnostico();
        String contenido = "Patient: " + cita.getPaciente().getUsuario().getNombre() +
                "\nFecha: " + FORMATO.format(cita.getFechaHora()) +
                "\nEspecialidad: " + cita.getMedico().getEspecialidad() +
                "\nSíntomas: " + nvl(cita.getTriageSintomas()) +
                "\n" + triage + piel +
                "\n\nRevisa ficha, historial y SOAP en el portal. Este resumen no sustituye tu criterio clínico.";
        inbox.crearParaEmail(cita.getMedico().getUsuario().getEmail(), asunto, contenido,
                "CITA_NUEVA", cita.getId());
        return enviar(cita.getMedico().getUsuario().getEmail(), asunto, contenido);
    }

    public boolean notificarCitaAceptada(Appointment cita) {
        String asunto = "Tu cita fue aceptada";
        String texto = "Hola " + cita.getPaciente().getUsuario().getNombre() + ",\n\n" +
                cita.getMedico().getUsuario().getNombre() + " aceptó tu cita del " +
                FORMATO.format(cita.getFechaHora()) + " (" + cita.getMedico().getEspecialidad() + ").\n\n" +
                "Revisa el detalle en tu portal Hospy.\n\n— Equipo Hospy";
        inbox.crearParaEmail(cita.getPaciente().getUsuario().getEmail(), asunto, texto,
                "CITA_ACEPTADA", cita.getId());
        return enviar(cita.getPaciente().getUsuario().getEmail(), asunto, texto);
    }

    public boolean notificarCitaRechazada(Appointment cita) {
        String asunto = "Tu cita no pudo ser aceptada";
        String texto = "Hola " + cita.getPaciente().getUsuario().getNombre() + ",\n\n" +
                "La cita del " + FORMATO.format(cita.getFechaHora()) + " con " +
                cita.getMedico().getUsuario().getNombre() + " fue rechazada. Agenda otro cupo en el portal.\n\n— Equipo Hospy";
        inbox.crearParaEmail(cita.getPaciente().getUsuario().getEmail(), asunto, texto,
                "CITA_RECHAZADA", cita.getId());
        return enviar(cita.getPaciente().getUsuario().getEmail(), asunto, texto);
    }

    public boolean notificarCitaCancelada(Appointment cita) {
        String asuntoMedico = "El paciente canceló una cita";
        String textoMedico = "Hola " + cita.getMedico().getUsuario().getNombre() + ",\n\n" +
                cita.getPaciente().getUsuario().getNombre() + " canceló la cita del " +
                FORMATO.format(cita.getFechaHora()) + " (" + cita.getMedico().getEspecialidad() + ").\n\n" +
                "El cupo quedó libre en tu agenda.\n\n— Equipo Hospy";
        String asuntoPaciente = "Cancelaste tu cita";
        String textoPaciente = "Hola " + cita.getPaciente().getUsuario().getNombre() + ",\n\n" +
                "Confirmamos la cancelación de tu cita del " + FORMATO.format(cita.getFechaHora()) +
                " con " + cita.getMedico().getUsuario().getNombre() + ".\n\n— Equipo Hospy";
        inbox.crearParaEmail(cita.getMedico().getUsuario().getEmail(), asuntoMedico, textoMedico,
                "CITA_CANCELADA", cita.getId());
        inbox.crearParaEmail(cita.getPaciente().getUsuario().getEmail(), asuntoPaciente, textoPaciente,
                "CITA_CANCELADA", cita.getId());
        enviar(cita.getPaciente().getUsuario().getEmail(), asuntoPaciente, textoPaciente);
        return enviar(cita.getMedico().getUsuario().getEmail(), asuntoMedico, textoMedico);
    }

    private static String nvl(String s) { return s == null || s.isBlank() ? "no informado" : s; }

    /** Avisa solo al paciente cuya cita se movió por otro caso más urgente. */
    public ResultadoNotificacion notificarRepriorizacion(Appointment citaDesplazada, LocalDateTime fechaAnterior,
                                                          int prioridadNuevoPaciente, String severidadNuevo) {
        String asuntoPaciente = "Tu cita médica fue reprogramada";
        String textoPaciente = "Hola " + citaDesplazada.getPaciente().getUsuario().getNombre() + ",\n\n" +
                "Tu cita con " + citaDesplazada.getMedico().getUsuario().getNombre() +
                " (" + citaDesplazada.getMedico().getEspecialidad() + ") que estaba programada para el " +
                FORMATO.format(fechaAnterior) + " fue reprogramada para el " +
                FORMATO.format(citaDesplazada.getFechaHora()) + ".\n\n" +
                "Motivo: ingresó un paciente con una urgencia mayor (prioridad " + prioridadNuevoPaciente +
                "/10, severidad " + severidadNuevo + ").\n\n" +
                "Revisa el detalle en tu portal Hospy. Si necesitas otra fecha, agenda de nuevo o contacta al centro.\n\n" +
                "— Equipo Hospy";

        inbox.crearParaEmail(citaDesplazada.getPaciente().getUsuario().getEmail(),
                "Tu cita fue reprogramada", textoPaciente, "CITA_REPROGRAMADA", citaDesplazada.getId());
        boolean paciente = enviar(citaDesplazada.getPaciente().getUsuario().getEmail(), asuntoPaciente, textoPaciente);
        return new ResultadoNotificacion(paciente, false);
    }

    private boolean enviar(String destinatario, String asunto, String contenido) {
        if (mailHost == null || mailHost.isBlank() || destinatario == null || destinatario.isBlank()) return false;
        JavaMailSender sender = senderProvider.getIfAvailable();
        if (sender == null) return false;
        try {
            SimpleMailMessage mensaje = new SimpleMailMessage();
            mensaje.setFrom(from);
            mensaje.setTo(destinatario);
            mensaje.setSubject(asunto);
            mensaje.setText(contenido);
            sender.send(mensaje);
            return true;
        } catch (RuntimeException ex) {
            log.warn("No se pudo enviar notificacion de cita: {}", ex.getMessage());
            return false;
        }
    }

    public record ResultadoNotificacion(boolean pacienteEnviado, boolean medicoEnviado) { }
}