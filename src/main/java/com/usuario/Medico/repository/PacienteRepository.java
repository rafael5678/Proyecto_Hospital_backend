package com.usuario.Medico.repository;

import com.usuario.Medico.model.Paciente;
import com.usuario.Medico.model.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PacienteRepository extends JpaRepository<Paciente, Long> {
    Optional<Paciente> findByUsuario(Usuario usuario);
    Optional<Paciente> findByUsuarioId(Long usuarioId);
    Optional<Paciente> findByUsuario_Email(String email);
}
