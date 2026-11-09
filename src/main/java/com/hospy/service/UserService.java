package com.hospy.service;

import com.hospy.config.SecretBox;
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

/** Admin user list, create, edit, and activate. */
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
    private final SecretBox secretBox;

    public List<UserDto> listAll() {
        List<UserDto> lista = new ArrayList<>();
        pacienteRepository.findAll().forEach(p -> lista.add(withReadablePassword(UserMapper.fromPaciente(p), p.getUsuario())));
        medicoRepository.findAll().forEach(m -> {
            UserDto dto = withReadablePassword(UserMapper.fromMedico(m), m.getUsuario());
            horarioService.fillSchedule(m, dto);
            lista.add(dto);
        });
        administradorRepository.findAll().forEach(a -> lista.add(withReadablePassword(UserMapper.fromAdministrador(a), a.getUsuario())));
        return lista;
    }

    public UserDto getById(Long id) {
        return UserMapper.fromUsuario(findStored(id));
    }

    @Transactional
    public UserDto create(UserRequest req) {
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
        String claveNueva = requirePassword(req.getPassword());
        User usuario = User.builder()
                .nombre(req.getNombre())
                .email(req.getEmail())
                .password(passwordEncoder.encode(claveNueva))
                .claveCifrada(secretBox.cifrar(claveNueva))
                .rol(rol)
                .telefono(ColombianPhone.normalize(req.getTelefono(), "El teléfono"))
                .activo(req.getActivo() != null ? req.getActivo() : true)
                .build();
        usuario = usuarioRepository.save(usuario);
        perfilService.createProfile(usuario, req);
        if (rol == Role.MEDICO) {
            medicoRepository.findByUsuario(usuario).ifPresent(medico -> {
                if (req.getDiasAtencion() != null && !req.getDiasAtencion().isBlank()
                        && req.getHoraInicio() != null && req.getHoraFin() != null) {
                    horarioService.defineSchedule(medico, req.getDiasAtencion(), req.getHoraInicio(),
                            req.getHoraFin(), req.getHoraInicio2(), req.getHoraFin2());
                } else {
                    horarioService.ensureWorkSchedule(medico);
                }
            });
        }
        return toDtoByRol(usuario);
    }

    @Transactional
    public UserDto update(Long id, UserRequest req) {
        User usuario = findStored(id);
        if (req.getNombre() == null || req.getNombre().isBlank()) {
            throw new RuntimeException("El nombre es obligatorio.");
        }
        if (req.getEmail() == null || !req.getEmail().contains("@")) {
            throw new RuntimeException("El correo es obligatorio.");
        }
        String email = req.getEmail().trim().toLowerCase();
        if (!email.equalsIgnoreCase(usuario.getEmail()) && usuarioRepository.existsByEmail(email)) {
            throw new RuntimeException("Ese correo ya está registrado.");
        }
        usuario.setNombre(req.getNombre().trim());
        usuario.setEmail(email);
        if (req.getTelefono() != null) {
            usuario.setTelefono(ColombianPhone.normalize(req.getTelefono(), "El teléfono"));
        }
        if (req.getActivo() != null) usuario.setActivo(req.getActivo());
        if (req.getPassword() != null && !req.getPassword().isBlank()) {
            String clave = requirePassword(req.getPassword());
            usuario.setPassword(passwordEncoder.encode(clave));
            usuario.setClaveCifrada(secretBox.cifrar(clave));
        }
        usuarioRepository.save(usuario);
        perfilService.updateProfile(usuario, req);
        if (usuario.getRol() == Role.MEDICO
                && req.getDiasAtencion() != null && !req.getDiasAtencion().isBlank()
                && req.getHoraInicio() != null && req.getHoraFin() != null) {
            medicoRepository.findByUsuario(usuario).ifPresent(medico ->
                    horarioService.defineSchedule(medico, req.getDiasAtencion(), req.getHoraInicio(),
                            req.getHoraFin(), req.getHoraInicio2(), req.getHoraFin2()));
        }
        return withReadablePassword(toDtoByRol(usuario), usuario);
    }

    private String requirePassword(String valor) {
        String clave = valor == null ? "" : valor;
        if (clave.chars().anyMatch(Character::isWhitespace)) {
            throw new RuntimeException("La contraseña no lleva espacios.");
        }
        if (clave.length() < 6 || clave.length() > 11) {
            throw new RuntimeException("La clave debe tener entre 6 y 11 caracteres.");
        }
        return clave;
    }

    private UserDto withReadablePassword(UserDto dto, User usuario) {
        if (usuario.getRol() != Role.ADMIN) {
            dto.setClave(secretBox.descifrar(usuario.getClaveCifrada()));
        }
        return dto;
    }

    @Transactional
    public void resetPassword(Long id, String nuevaClave, String claveAdmin, String emailAdmin) {
        if (claveAdmin == null || claveAdmin.isBlank()) {
            throw new RuntimeException("Escribe tu contraseña de administrador para confirmar.");
        }
        User admin = usuarioRepository.findByEmail(emailAdmin)
                .orElseThrow(() -> new RuntimeException("Administrador no encontrado"));
        if (admin.getRol() != Role.ADMIN || !passwordEncoder.matches(claveAdmin, admin.getPassword())) {
            throw new RuntimeException("La contraseña de administrador no coincide.");
        }
        User usuario = findStored(id);
        if (usuario.getRol() == Role.ADMIN) {
            throw new RuntimeException("La clave del administrador no se cambia desde esta pantalla.");
        }
        String clave = requirePassword(nuevaClave);
        usuario.setPassword(passwordEncoder.encode(clave));
        usuario.setClaveCifrada(secretBox.cifrar(clave));
        usuarioRepository.save(usuario);
    }

    public void remove(Long id) {
        changeActive(id, false);
    }

    @Transactional
    public UserDto changeActive(Long id, boolean activo) {
        User usuario = findStored(id);
        if (usuario.getRol() == Role.ADMIN) {
            throw new RuntimeException("El administrador único no se puede desactivar.");
        }
        usuario.setActivo(activo);
        usuarioRepository.save(usuario);
        return toDtoByRol(usuario);
    }

    public List<UserDto> listEveryDoctor() {
        return medicoRepository.findAll().stream().map(UserMapper::fromMedico).toList();
    }

    public User findStored(Long id) {
        return usuarioRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User no encontrado"));
    }

    public User findByEmail(String email) {
        return usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User no encontrado"));
    }

    public List<UserDto> listDoctors() {
        return medicoRepository.findByUsuario_ActivoTrue()
                .stream().map(UserMapper::fromMedico).toList();
    }

    @Transactional
    public UserDto updateDoctor(Long medicoId, UserRequest req) {
        Doctor medico = medicoRepository.findById(medicoId)
                .orElseThrow(() -> new RuntimeException("Médico no encontrado"));
        return update(medico.getUsuario().getId(), req);
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
