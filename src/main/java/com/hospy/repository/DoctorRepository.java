package com.hospy.repository;

import com.hospy.model.Doctor;
import com.hospy.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface DoctorRepository extends JpaRepository<Doctor, Long> {
    Optional<Doctor> findByUsuario(User usuario);
    Optional<Doctor> findByUsuarioId(Long usuarioId);
    Optional<Doctor> findByUsuario_Email(String email);
    List<Doctor> findByUsuario_ActivoTrue();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Doctor m WHERE m.id = :id")
    Optional<Doctor> findByIdForUpdate(@Param("id") Long id);
}
