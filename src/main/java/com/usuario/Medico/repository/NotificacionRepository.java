package com.usuario.Medico.repository;

import com.usuario.Medico.model.Notificacion;
import com.usuario.Medico.model.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificacionRepository extends JpaRepository<Notificacion, Long> {
    List<Notificacion> findByUsuarioOrderByCreadaEnDesc(Usuario usuario);
    long countByUsuarioAndLeidaFalse(Usuario usuario);
    boolean existsByCitaIdAndTipo(Long citaId, String tipo);
}
