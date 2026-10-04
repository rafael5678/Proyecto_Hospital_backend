package com.usuario.Medico.service;

import com.usuario.Medico.config.JwtService;
import com.usuario.Medico.dto.AuthRequest;
import com.usuario.Medico.dto.AuthResponse;
import com.usuario.Medico.dto.RegisterRequest;
import com.usuario.Medico.model.Rol;
import com.usuario.Medico.model.Usuario;
import com.usuario.Medico.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final PerfilService perfilService;

    /** HU-01: Registro sin JWT — el paciente debe iniciar sesión después */
    @Transactional
    public void registrar(RegisterRequest req) {
        if (usuarioRepository.existsByEmail(req.getEmail())) {
            throw new RuntimeException("El email ya está registrado");
        }
        Usuario usuario = Usuario.builder()
                .nombre(req.getNombre())
                .email(req.getEmail())
                .password(passwordEncoder.encode(req.getPassword()))
                .rol(Rol.PACIENTE)
                .telefono(req.getTelefono())
                .activo(true)
                .build();
        usuario = usuarioRepository.save(usuario);
        perfilService.crearPaciente(usuario, req);
    }

    /** HU-02: Login con JWT */
    public AuthResponse login(AuthRequest req) {
        Usuario usuario = usuarioRepository.findByEmail(resolverEmailLogin(req.getEmail(), req.getRol()))
                .orElseThrow(() -> new RuntimeException("Credenciales inválidas"));
        if (!passwordEncoder.matches(req.getPassword(), usuario.getPassword())) {
            throw new RuntimeException("Credenciales inválidas");
        }
        if (!Boolean.TRUE.equals(usuario.getActivo())) {
            throw new RuntimeException("Usuario desactivado");
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

// Documentación de soporte AuthService: Emite tokens JWT con claims de usuario y rol tras validación criptográfica.
