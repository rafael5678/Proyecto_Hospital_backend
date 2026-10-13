package com.hospy.repository;

import com.hospy.model.Notification;
import com.hospy.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findByUsuarioOrderByCreadaEnDesc(User usuario);
    long countByUsuarioAndLeidaFalse(User usuario);
    boolean existsByCitaIdAndTipo(Long citaId, String tipo);
}
