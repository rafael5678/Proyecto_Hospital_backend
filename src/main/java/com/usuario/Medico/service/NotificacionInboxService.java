package com.usuario.Medico.service;

import com.usuario.Medico.dto.NotificacionResponse;
import com.usuario.Medico.model.EstadoCita;
import com.usuario.Medico.model.Notificacion;
import com.usuario.Medico.model.Usuario;
import com.usuario.Medico.repository.CitaRepository;
import com.usuario.Medico.repository.NotificacionRepository;
import com.usuario.Medico.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificacionInboxService {

    private final NotificacionRepository notificacionRepository;
    private final UsuarioRepository usuarioRepository;
    private final CitaRepository citaRepository;

    @Transactional
    public void crearParaEmail(String email, String titulo, String mensaje, String tipo, Long citaId) {
        if (email == null || email.isBlank()) return;
        usuarioRepository.findByEmail(email).ifPresent(u -> crear(u, titulo, mensaje, tipo, citaId));
    }

    @Transactional
    public void crear(Usuario usuario, String titulo, String mensaje, String tipo, Long citaId) {
        if (citaId != null && notificacionRepository.existsByCitaIdAndTipo(citaId, tipo)) {
            return;
        }
        notificacionRepository.save(Notificacion.builder()
                .usuario(usuario)
                .titulo(titulo)
                .mensaje(mensaje.length() > 800 ? mensaje.substring(0, 797) + "..." : mensaje)
                .tipo(tipo)
                .citaId(citaId)
                .leida(false)
                .creadaEn(LocalDateTime.now())
                .build());
    }

    public List<NotificacionResponse> listar(String email) {
        Usuario u = usuario(email);
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
        Usuario u = usuario(email);
        notificacionRepository.findById(id).ifPresent(n -> {
            if (n.getUsuario().getId().equals(u.getId())) {
                n.setLeida(true);
                notificacionRepository.save(n);
            }
        });
    }

    @Transactional
    public void avisarAceptadasSinAviso() {
        citaRepository.findByEstado(EstadoCita.ACEPTADA).forEach(cita -> {
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
        Usuario u = usuario(email);
        notificacionRepository.findByUsuarioOrderByCreadaEnDesc(u).forEach(n -> {
            if (!Boolean.TRUE.equals(n.getLeida())) {
                n.setLeida(true);
                notificacionRepository.save(n);
            }
        });
    }

    private Usuario usuario(String email) {
        return usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));
    }

    private NotificacionResponse toDto(Notificacion n) {
        return NotificacionResponse.builder()
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
