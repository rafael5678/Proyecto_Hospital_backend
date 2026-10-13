package com.hospy.service;

import com.hospy.dto.NotificationResponse;
import com.hospy.model.AppointmentStatus;
import com.hospy.model.Notification;
import com.hospy.model.User;
import com.hospy.repository.AppointmentRepository;
import com.hospy.repository.NotificationRepository;
import com.hospy.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationInboxService {

    private final NotificationRepository notificacionRepository;
    private final UserRepository usuarioRepository;
    private final AppointmentRepository citaRepository;

    @Transactional
    public void crearParaEmail(String email, String titulo, String mensaje, String tipo, Long citaId) {
        if (email == null || email.isBlank()) return;
        usuarioRepository.findByEmail(email).ifPresent(u -> crear(u, titulo, mensaje, tipo, citaId));
    }

    @Transactional
    public void crear(User usuario, String titulo, String mensaje, String tipo, Long citaId) {
        notificacionRepository.save(Notification.builder()
                .usuario(usuario)
                .titulo(titulo)
                .mensaje(mensaje.length() > 800 ? mensaje.substring(0, 797) + "..." : mensaje)
                .tipo(tipo)
                .citaId(citaId)
                .leida(false)
                .creadaEn(LocalDateTime.now())
                .build());
    }

    public List<NotificationResponse> listar(String email) {
        User u = usuario(email);
        return notificacionRepository.findByUsuarioOrderByCreadaEnDesc(u).stream()
                .limit(30)
                .map(this::toDto)
                .toList();
    }

    public long noLeidas(String email) {
        return notificacionRepository.countByUsuarioAndLeidaFalse(usuario(email));
    }

    @Transactional
    public void marcarLeida(Long id, String email) {
        User u = usuario(email);
        notificacionRepository.findById(id).ifPresent(n -> {
            if (n.getUsuario().getId().equals(u.getId())) {
                n.setLeida(true);
                notificacionRepository.save(n);
            }
        });
    }

    @Transactional
    public void avisarAceptadasSinAviso() {
        citaRepository.findByEstado(AppointmentStatus.ACEPTADA).forEach(cita -> {
            if (cita.getId() == null || notificacionRepository.existsByCitaIdAndTipo(cita.getId(), "CITA_ACEPTADA")) {
                return;
            }
            String nombre = cita.getPaciente().getUsuario().getNombre();
            String medico = cita.getMedico().getUsuario().getNombre();
            String especialidad = cita.getMedico().getEspecialidad();
            crear(cita.getPaciente().getUsuario(),
                    "Tu cita fue aceptada",
                    "Hola " + nombre + ", " + medico + " aceptó tu cita de " + especialidad
                            + ". Revisa el detalle en tu portal Hospy.",
                    "CITA_ACEPTADA",
                    cita.getId());
        });
    }

    @Transactional
    public void marcarTodas(String email) {
        User u = usuario(email);
        notificacionRepository.findByUsuarioOrderByCreadaEnDesc(u).forEach(n -> {
            if (!Boolean.TRUE.equals(n.getLeida())) {
                n.setLeida(true);
                notificacionRepository.save(n);
            }
        });
    }

    private User usuario(String email) {
        return usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User no encontrado"));
    }

    private NotificationResponse toDto(Notification n) {
        return NotificationResponse.builder()
                .id(n.getId())
                .titulo(n.getTitulo())
                .mensaje(n.getMensaje())
                .tipo(n.getTipo())
                .leida(n.getLeida())
                .citaId(n.getCitaId())
                .creadaEn(n.getCreadaEn())
                .build();
    }
}
