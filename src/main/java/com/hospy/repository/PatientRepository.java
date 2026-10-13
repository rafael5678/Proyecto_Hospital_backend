package com.hospy.repository;

import com.hospy.model.Patient;
import com.hospy.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PatientRepository extends JpaRepository<Patient, Long> {
    Optional<Patient> findByUsuario(User usuario);
    Optional<Patient> findByUsuarioId(Long usuarioId);
    Optional<Patient> findByUsuario_Email(String email);
}
