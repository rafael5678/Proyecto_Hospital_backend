package com.usuario.Medico.repository;

import com.usuario.Medico.model.Medico;
import com.usuario.Medico.model.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface MedicoRepository extends JpaRepository<Medico, Long> {
    Optional<Medico> findByUsuario(Usuario usuario);
    Optional<Medico> findByUsuarioId(Long usuarioId);
    Optional<Medico> findByUsuario_Email(String email);
    List<Medico> findByUsuario_ActivoTrue();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Medico m WHERE m.id = :id")
    Optional<Medico> findByIdForUpdate(@Param("id") Long id);
}
