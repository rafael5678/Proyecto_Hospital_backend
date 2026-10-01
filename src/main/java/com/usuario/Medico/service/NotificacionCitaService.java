package com.usuario.Medico.service;

import com.usuario.Medico.model.Cita;
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
public class NotificacionCitaService {
    private static final DateTimeFormatter FORMATO = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final ObjectProvider<JavaMailSender> senderProvider;

    @Value("${spring.mail.host:}")
    private String mailHost;

    @Value("${app.mail.from:noreply@hospy.local}")
    private String from;

    public NotificacionCitaService(ObjectProvider<JavaMailSender> senderProvider) {
        this.senderProvider = senderProvider;
    }

    public ResultadoNotificacion notificarCambio(Cita cita, LocalDateTime fechaAnterior, String motivo) {
        String asunto = "Cambio en tu cita médica";
        String textoPaciente = "Hola " + cita.getPaciente().getUsuario().getNombre() + ",\n\n" +
                "La cita con " + cita.getMedico().getUsuario().getNombre() +
                " cambió de " + FORMATO.format(fechaAnterior) + " a " + FORMATO.format(cita.getFechaHora()) +
                ".\nMotivo: " + motivo + "\n\nSi necesitas ayuda, contacta al centro médico.";
        String textoMedico = "Hola " + cita.getMedico().getUsuario().getNombre() + ",\n\n" +
                "La cita de " + cita.getPaciente().getUsuario().getNombre() +
                " cambió de " + FORMATO.format(fechaAnterior) + " a " + FORMATO.format(cita.getFechaHora()) +
                ".\nMotivo administrativo: " + motivo;

        boolean paciente = enviar(cita.getPaciente().getUsuario().getEmail(), asunto, textoPaciente);
        boolean medico = enviar(cita.getMedico().getUsuario().getEmail(), asunto, textoMedico);
        return new ResultadoNotificacion(paciente, medico);
    }

    public boolean notificarNuevaCita(Cita cita) {
        String asunto = "Nueva cita — resumen IA para revisión";
        String triage = cita.getTriageResumen() == null ? "Sin triage" :
                "Severidad " + nvl(cita.getTriageSeveridad()) +
                ", prioridad " + (cita.getTriagePrioridad() == null ? "-" : cita.getTriagePrioridad()) +
                "/10, ESI " + (cita.getTriageNivelEsi() == null ? "-" : cita.getTriageNivelEsi()) +
                ". " + cita.getTriageResumen();
        String piel = cita.getDermatologiaTopDiagnostico() == null ? "" :
                "\nPiel: " + cita.getDermatologiaTopDiagnostico();
        String contenido = "Paciente: " + cita.getPaciente().getUsuario().getNombre() +
                "\nFecha: " + FORMATO.format(cita.getFechaHora()) +
                "\nEspecialidad: " + cita.getMedico().getEspecialidad() +
                "\nSíntomas: " + nvl(cita.getTriageSintomas()) +
                "\n" + triage + piel +
                "\n\nRevisa ficha, historial y SOAP en el portal. Este resumen no sustituye tu criterio clínico.";
        return enviar(cita.getMedico().getUsuario().getEmail(), asunto, contenido);
    }

    private static String nvl(String s) { return s == null || s.isBlank() ? "no informado" : s; }

    /**
     * Notifica al paciente desplazado y al médico cuando una cita fue movida
     * porque ingresó un paciente con mayor prioridad de triage.
     */
    public ResultadoNotificacion notificarRepriorizacion(Cita citaDesplazada, LocalDateTime fechaAnterior,
                                                          int prioridadNuevoPaciente, String severidadNuevo) {
        String asuntoPaciente = "⚠ Tu cita médica fue reprogramada";
        String textoPaciente = "Hola " + citaDesplazada.getPaciente().getUsuario().getNombre() + ",\n\n" +
                "Tu cita con " + citaDesplazada.getMedico().getUsuario().getNombre() +
                " (" + citaDesplazada.getMedico().getEspecialidad() + ") que estaba programada para el " +
                FORMATO.format(fechaAnterior) + " fue reprogramada para el " +
                FORMATO.format(citaDesplazada.getFechaHora()) + ".\n\n" +
                "Motivo: Ingresó un paciente con una urgencia mayor (prioridad " + prioridadNuevoPaciente +
                "/10, severidad " + severidadNuevo + ") que requiere atención prioritaria.\n\n" +
                "Lamentamos la inconveniencia. Si necesitas cambiar la nueva fecha, " +
                "ingresa al portal de Hospy o contacta al centro médico.\n\n" +
                "— Equipo Hospy";

        String asuntoMedico = "📋 Cita reprogramada por priorización IA";
        String textoMedico = "Hola " + citaDesplazada.getMedico().getUsuario().getNombre() + ",\n\n" +
                "La cita de " + citaDesplazada.getPaciente().getUsuario().getNombre() +
                " fue movida de " + FORMATO.format(fechaAnterior) + " a " +
                FORMATO.format(citaDesplazada.getFechaHora()) + ".\n\n" +
                "Un paciente con prioridad " + prioridadNuevoPaciente + "/10 (severidad " + severidadNuevo +
                ") tomó el horario anterior por urgencia clínica mayor.\n\n" +
                "Revisa tu agenda actualizada en el portal de Hospy.";

        boolean paciente = enviar(citaDesplazada.getPaciente().getUsuario().getEmail(), asuntoPaciente, textoPaciente);
        boolean medico = enviar(citaDesplazada.getMedico().getUsuario().getEmail(), asuntoMedico, textoMedico);
        return new ResultadoNotificacion(paciente, medico);
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