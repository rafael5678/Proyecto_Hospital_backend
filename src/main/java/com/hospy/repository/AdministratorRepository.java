package com.hospy.repository;

import com.hospy.model.Administrator;
import com.hospy.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Spring Data access for table administradores. */
public interface AdministratorRepository extends JpaRepository<Administrator, Long> {
    Optional<Administrator> findByUsuario(User usuario);
    Optional<Administrator> findByUsuarioId(Long usuarioId);
}
