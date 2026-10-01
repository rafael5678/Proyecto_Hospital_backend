package com.usuario.Medico.repository;

import com.usuario.Medico.model.Administrador;
import com.usuario.Medico.model.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdministradorRepository extends JpaRepository<Administrador, Long> {
    Optional<Administrador> findByUsuario(Usuario usuario);
    Optional<Administrador> findByUsuarioId(Long usuarioId);
}
