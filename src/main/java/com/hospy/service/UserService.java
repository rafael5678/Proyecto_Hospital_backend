package com.hospy.service;

import com.hospy.dto.UserDto;
import com.hospy.dto.UserRequest;
import com.hospy.model.Doctor;
import com.hospy.model.Role;
import com.hospy.model.User;
import com.hospy.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository usuarioRepository;
    private final PatientRepository pacienteRepository;
    private final DoctorRepository medicoRepository;
    private final AdministratorRepository administradorRepository;
    private final PasswordEncoder passwordEncoder;
    private final ProfileService perfilService;
    private final ScheduleService horarioService;

    public List<UserDto> listarTodos() {
        List<UserDto> lista = new ArrayList<>();
        pacienteRepository.findAll().forEach(p -> lista.add(UserMapper.fromPaciente(p)));
        medicoRepository.findAll().forEach(m -> lista.add(UserMapper.fromMedico(m)));
        administradorRepository.findAll().forEach(a -> lista.add(UserMapper.fromAdministrador(a)));
        return lista;
    }

    public UserDto obtenerPorId(Long id) {
        return UserMapper.fromUsuario(buscar(id));
    }

    @Transactional
    public UserDto crear(UserRequest req) {
        if (usuarioRepository.existsByEmail(req.getEmail())) {
            throw new RuntimeException("El email ya existe");
        }
        Role rol = Role.valueOf(req.getRol().toUpperCase());
        if (rol == Role.ADMIN) {
            throw new RuntimeException("Solo existe un administrador. No se pueden crear más por seguridad.");
        }
        if (rol == Role.MEDICO && (req.getEspecialidad() == null || req.getEspecialidad().isBlank())) {
            throw new RuntimeException("El médico requiere especialidad.");
        }
        if (rol == Role.PACIENTE && (req.getDocumento() == null || req.getDocumento().isBlank())) {
            throw new RuntimeException("El paciente requiere documento de identidad.");
        }
        if (req.getPassword() == null || req.getPassword().isBlank()) {
            throw new RuntimeException("Indica la contraseña.");
        }
        User usuario = User.builder()
                .nombre(req.getNombre())
                .email(req.getEmail())
                .password(passwordEncoder.encode(req.getPassword()))
                .rol(rol)
                .telefono(req.getTelefono())
                .activo(req.getActivo() != null ? req.getActivo() : true)
                .build();
        usuario = usuarioRepository.save(usuario);
        perfilService.crearPerfil(usuario, req);
        if (rol == Role.MEDICO) {
            medicoRepository.findByUsuario(usuario).ifPresent(medico -> {
                if (req.getDiasAtencion() != null && !req.getDiasAtencion().isBlank()
                        && req.getHoraInicio() != null && req.getHoraFin() != null) {
                    horarioService.definirHorario(medico, req.getDiasAtencion(), req.getHoraInicio(),
                            req.getHoraFin(), req.getHoraInicio2(), req.getHoraFin2());
                } else {
                    horarioService.asegurarHorarioLaboral(medico);
                }
            });
        }
        return toDtoByRol(usuario);
    }

    @Transactional
    public UserDto actualizar(Long id, UserRequest req) {
        User usuario = buscar(id);
        usuario.setNombre(req.getNombre());
        if (req.getTelefono() != null) usuario.setTelefono(req.getTelefono());
        if (req.getActivo() != null) usuario.setActivo(req.getActivo());
        if (req.getPassword() != null && !req.getPassword().isBlank()) {
            throw new RuntimeException("Para cambiar una clave confirma con tu contraseña de administrador en Accesos y claves.");
        }
        usuarioRepository.save(usuario);
        perfilService.actualizarPerfil(usuario, req);
        return toDtoByRol(usuario);
    }

    @Transactional
    public void restablecerClave(Long id, String nuevaClave, String claveAdmin, String emailAdmin) {
        if (claveAdmin == null || claveAdmin.isBlank()) {
            throw new RuntimeException("Escribe tu contraseña de administrador para confirmar.");
        }
        User admin = usuarioRepository.findByEmail(emailAdmin)
                .orElseThrow(() -> new RuntimeException("Administrador no encontrado"));
        if (admin.getRol() != Role.ADMIN || !passwordEncoder.matches(claveAdmin, admin.getPassword())) {
            throw new RuntimeException("La contraseña de administrador no coincide.");
        }
        User usuario = buscar(id);
        if (usuario.getRol() == Role.ADMIN) {
            throw new RuntimeException("La clave del administrador no se cambia desde esta pantalla.");
        }
        String clave = nuevaClave == null ? "" : nuevaClave.trim();
        if (clave.length() < 6) {
            throw new RuntimeException("La nueva clave debe tener al menos 6 caracteres.");
        }
        usuario.setPassword(passwordEncoder.encode(clave));
        usuarioRepository.save(usuario);
    }

    public void eliminar(Long id) {
        cambiarEstado(id, false);
    }

    @Transactional
    public UserDto cambiarEstado(Long id, boolean activo) {
        User usuario = buscar(id);
        if (usuario.getRol() == Role.ADMIN) {
            throw new RuntimeException("El administrador único no se puede desactivar.");
        }
        usuario.setActivo(activo);
        usuarioRepository.save(usuario);
        return toDtoByRol(usuario);
    }

    public List<UserDto> listarTodosLosMedicos() {
        return medicoRepository.findAll().stream().map(UserMapper::fromMedico).toList();
    }

    public User buscar(Long id) {
        return usuarioRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User no encontrado"));
    }

    public User buscarPorEmail(String email) {
        return usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User no encontrado"));
    }

    public List<UserDto> listarMedicos() {
        return medicoRepository.findByUsuario_ActivoTrue()
                .stream().map(UserMapper::fromMedico).toList();
    }

    @Transactional
    public UserDto actualizarMedico(Long medicoId, UserRequest req) {
        Doctor medico = medicoRepository.findById(medicoId)
                .orElseThrow(() -> new RuntimeException("Médico no encontrado"));
        return actualizar(medico.getUsuario().getId(), req);
    }

    private UserDto toDtoByRol(User usuario) {
        return switch (usuario.getRol()) {
            case PACIENTE -> pacienteRepository.findByUsuario(usuario)
                    .map(UserMapper::fromPaciente)
                    .orElse(UserMapper.fromUsuario(usuario));
            case MEDICO -> medicoRepository.findByUsuario(usuario)
                    .map(UserMapper::fromMedico)
                    .orElse(UserMapper.fromUsuario(usuario));
            case ADMIN -> administradorRepository.findByUsuario(usuario)
                    .map(UserMapper::fromAdministrador)
                    .orElse(UserMapper.fromUsuario(usuario));
        };
    }
}
