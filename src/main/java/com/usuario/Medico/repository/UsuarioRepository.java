package com.usuario.Medico.repository;

import com.usuario.Medico.model.Rol;
import com.usuario.Medico.model.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {
    Optional<Usuario> findByEmail(String email);
    boolean existsByEmail(String email);
    List<Usuario> findByRol(Rol rol);
    List<Usuario> findByRolAndActivoTrue(Rol rol);
}
