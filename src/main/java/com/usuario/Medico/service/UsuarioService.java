package com.usuario.Medico.service;

import com.usuario.Medico.dto.UsuarioDTO;
import com.usuario.Medico.dto.UsuarioRequest;
import com.usuario.Medico.model.Medico;
import com.usuario.Medico.model.Rol;
import com.usuario.Medico.model.Usuario;
import com.usuario.Medico.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UsuarioService {

    private final UsuarioRepository usuarioRepository;
    private final PacienteRepository pacienteRepository;
    private final MedicoRepository medicoRepository;
    private final AdministradorRepository administradorRepository;
    private final PasswordEncoder passwordEncoder;
    private final PerfilService perfilService;

    public List<UsuarioDTO> listarTodos() {
        List<UsuarioDTO> lista = new ArrayList<>();
        pacienteRepository.findAll().forEach(p -> lista.add(UsuarioMapper.fromPaciente(p)));
        medicoRepository.findAll().forEach(m -> lista.add(UsuarioMapper.fromMedico(m)));
        administradorRepository.findAll().forEach(a -> lista.add(UsuarioMapper.fromAdministrador(a)));
        return lista;
    }

    public UsuarioDTO obtenerPorId(Long id) {
        return UsuarioMapper.fromUsuario(buscar(id));
    }

    @Transactional
    public UsuarioDTO crear(UsuarioRequest req) {
        if (usuarioRepository.existsByEmail(req.getEmail())) {
            throw new RuntimeException("El email ya existe");
        }
        Rol rol = Rol.valueOf(req.getRol().toUpperCase());
        if (rol == Rol.ADMIN) {
            throw new RuntimeException("Solo existe un administrador. No se pueden crear más por seguridad.");
        }
        if (rol == Rol.MEDICO && (req.getEspecialidad() == null || req.getEspecialidad().isBlank())) {
            throw new RuntimeException("El médico requiere especialidad.");
        }
        if (rol == Rol.PACIENTE && (req.getDocumento() == null || req.getDocumento().isBlank())) {
            throw new RuntimeException("El paciente requiere documento de identidad.");
        }
        Usuario usuario = Usuario.builder()
                .nombre(req.getNombre())
                .email(req.getEmail())
                .password(passwordEncoder.encode(
                        req.getPassword() != null && !req.getPassword().isBlank()
                                ? req.getPassword() : "Medico123"))
                .rol(rol)
                .telefono(req.getTelefono())
                .activo(req.getActivo() != null ? req.getActivo() : true)
                .build();
        usuario = usuarioRepository.save(usuario);
        perfilService.crearPerfil(usuario, req);
        return toDtoByRol(usuario);
    }

    @Transactional
    public UsuarioDTO actualizar(Long id, UsuarioRequest req) {
        Usuario usuario = buscar(id);
        usuario.setNombre(req.getNombre());
        usuario.setTelefono(req.getTelefono());
        if (req.getActivo() != null) usuario.setActivo(req.getActivo());
        if (req.getPassword() != null && !req.getPassword().isBlank()) {
            usuario.setPassword(passwordEncoder.encode(req.getPassword()));
        }
        usuarioRepository.save(usuario);
        perfilService.actualizarPerfil(usuario, req);
        return toDtoByRol(usuario);
    }

    public void eliminar(Long id) {
        cambiarEstado(id, false);
    }

    @Transactional
    public UsuarioDTO cambiarEstado(Long id, boolean activo) {
        Usuario usuario = buscar(id);
        if (usuario.getRol() == Rol.ADMIN) {
            throw new RuntimeException("El administrador único no se puede desactivar.");
        }
        usuario.setActivo(activo);
        usuarioRepository.save(usuario);
        return toDtoByRol(usuario);
    }

    public List<UsuarioDTO> listarTodosLosMedicos() {
        return medicoRepository.findAll().stream().map(UsuarioMapper::fromMedico).toList();
    }

    public Usuario buscar(Long id) {
        return usuarioRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));
    }

    public Usuario buscarPorEmail(String email) {
        return usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));
    }

    public List<UsuarioDTO> listarMedicos() {
        return medicoRepository.findByUsuario_ActivoTrue()
                .stream().map(UsuarioMapper::fromMedico).toList();
    }

    @Transactional
    public UsuarioDTO actualizarMedico(Long medicoId, UsuarioRequest req) {
        Medico medico = medicoRepository.findById(medicoId)
                .orElseThrow(() -> new RuntimeException("Médico no encontrado"));
        return actualizar(medico.getUsuario().getId(), req);
    }

    private UsuarioDTO toDtoByRol(Usuario usuario) {
        return switch (usuario.getRol()) {
            case PACIENTE -> pacienteRepository.findByUsuario(usuario)
                    .map(UsuarioMapper::fromPaciente)
                    .orElse(UsuarioMapper.fromUsuario(usuario));
            case MEDICO -> medicoRepository.findByUsuario(usuario)
                    .map(UsuarioMapper::fromMedico)
                    .orElse(UsuarioMapper.fromUsuario(usuario));
            case ADMIN -> administradorRepository.findByUsuario(usuario)
                    .map(UsuarioMapper::fromAdministrador)
                    .orElse(UsuarioMapper.fromUsuario(usuario));
        };
    }
}

// Documentación de soporte UsuarioService: Controla el ciclo de vida de cuentas y sincronización de roles.
