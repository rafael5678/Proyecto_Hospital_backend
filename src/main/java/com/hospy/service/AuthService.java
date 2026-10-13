package com.hospy.service;

import com.hospy.config.JwtService;
import com.hospy.dto.AuthRequest;
import com.hospy.dto.AuthResponse;
import com.hospy.dto.RegisterRequest;
import com.hospy.model.Role;
import com.hospy.model.User;
import com.hospy.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final ProfileService perfilService;

    /** Public patient registration. Doctors and admins are created by the hospital admin. */
    @Transactional
    public void registrar(RegisterRequest req) {
        if (usuarioRepository.existsByEmail(req.getEmail())) {
            throw new RuntimeException("El email ya está registrado");
        }
        User usuario = User.builder()
                .nombre(req.getNombre())
                .email(req.getEmail())
                .password(passwordEncoder.encode(req.getPassword()))
                .rol(Role.PACIENTE)
                .telefono(req.getTelefono())
                .activo(true)
                .build();
        usuario = usuarioRepository.save(usuario);
        perfilService.crearPaciente(usuario, req);
    }

    /** Single login: the stored role decides which portal opens. */
    public AuthResponse login(AuthRequest req) {
        User usuario = usuarioRepository.findByEmail(resolverEmailLogin(req.getEmail(), req.getRol()))
                .orElseThrow(() -> new RuntimeException("Credenciales inválidas"));
        if (!passwordEncoder.matches(req.getPassword(), usuario.getPassword())) {
            throw new RuntimeException("Credenciales inválidas");
        }
        if (!Boolean.TRUE.equals(usuario.getActivo())) {
            throw new RuntimeException("User desactivado");
        }
        if (req.getRol() != null && !req.getRol().isBlank()
                && !usuario.getRol().name().equalsIgnoreCase(req.getRol())) {
            throw new RuntimeException("No tienes acceso a este portal");
        }
        String token = jwtService.generarToken(
                usuario.getEmail(),
                usuario.getRol().name(),
                usuario.getId()
        );
        return AuthResponse.builder()
                .token(token)
                .tipo("Bearer")
                .id(usuario.getId())
                .nombre(usuario.getNombre())
                .email(usuario.getEmail())
                .rol(usuario.getRol().name())
                .build();
    }

    /** Admin demo también entra con usuario "admin"; médico demo con "doctor". */
    private String resolverEmailLogin(String identificador, String rol) {
        if (identificador == null) {
            return "";
        }
        String valor = identificador.trim();
        if (valor.contains("@")) {
            return valor;
        }
        if ("admin".equalsIgnoreCase(valor)
                && (rol == null || rol.isBlank() || "ADMIN".equalsIgnoreCase(rol))) {
            return "admin@hospy.com";
        }
        if ("doctor".equalsIgnoreCase(valor)
                && (rol == null || rol.isBlank() || "MEDICO".equalsIgnoreCase(rol))) {
            return "doctor@hospy.com";
        }
        return valor;
    }
}
