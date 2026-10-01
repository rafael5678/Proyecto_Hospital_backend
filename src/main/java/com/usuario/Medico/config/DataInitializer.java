package com.usuario.Medico.config;

import com.usuario.Medico.dto.RegisterRequest;
import com.usuario.Medico.dto.UsuarioRequest;
import com.usuario.Medico.model.Administrador;
import com.usuario.Medico.model.Rol;
import com.usuario.Medico.model.Usuario;
import com.usuario.Medico.repository.AdministradorRepository;
import com.usuario.Medico.repository.MedicoRepository;
import com.usuario.Medico.repository.PacienteRepository;
import com.usuario.Medico.repository.UsuarioRepository;
import com.usuario.Medico.service.PerfilService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UsuarioRepository usuarioRepository;
    private final AdministradorRepository administradorRepository;
    private final PacienteRepository pacienteRepository;
    private final MedicoRepository medicoRepository;
    private final PasswordEncoder passwordEncoder;
    private final PerfilService perfilService;

    @Override
    @Transactional
    public void run(String... args) {
        asegurarAdminDemo();
        migrarPerfilesExistentes();
        asegurarMedicoDemo();
        asegurarMedico("neurologia@hospy.com", "Dra. Ana López", "Neurología", "MED-2024-002");
        asegurarMedico("dermatologia@hospy.com", "Dr. Carlos Pérez", "Dermatología", "MED-2024-003");
        asegurarPacienteDemo("pedro@hospy.com", "Pedro Paciente", "123456");
        asegurarPacienteDemo("paciente@hospy.com", "Juan Reyes", "Paciente123");
    }

    private void asegurarPacienteDemo(String email, String nombre, String clave) {
        usuarioRepository.findByEmail(email).ifPresentOrElse(u -> {
            u.setPassword(passwordEncoder.encode(clave));
            u.setRol(Rol.PACIENTE);
            u.setActivo(true);
            u.setNombre(nombre);
            usuarioRepository.save(u);
            if (pacienteRepository.findByUsuario(u).isEmpty()) {
                RegisterRequest r = new RegisterRequest();
                r.setDocumento("CC-DEMO");
                perfilService.crearPaciente(u, r);
            }
        }, () -> {
            Usuario u = usuarioRepository.save(Usuario.builder()
                    .nombre(nombre).email(email)
                    .password(passwordEncoder.encode(clave))
                    .rol(Rol.PACIENTE).activo(true).build());
            RegisterRequest r = new RegisterRequest();
            r.setDocumento("CC-DEMO");
            perfilService.crearPaciente(u, r);
        });
    }

    private void asegurarMedico(String email, String nombre, String especialidad, String licencia) {
        if (usuarioRepository.findByEmail(email).isPresent()) return;
        UsuarioRequest req = new UsuarioRequest();
        req.setNombre(nombre);
        req.setEmail(email);
        req.setPassword("Medico123");
        req.setRol("MEDICO");
        req.setEspecialidad(especialidad);
        req.setNumeroLicencia(licencia);
        Usuario u = usuarioRepository.save(Usuario.builder()
                .nombre(nombre).email(email)
                .password(passwordEncoder.encode("Medico123"))
                .rol(Rol.MEDICO).activo(true).build());
        perfilService.crearPerfil(u, req);
    }

    /** Credenciales fijas de demo (se reactivan y restablecen al arrancar). */
    private void asegurarAdminDemo() {
        usuarioRepository.findByEmail("admin@hospy.com").ifPresentOrElse(u -> {
            u.setPassword(passwordEncoder.encode("admin123"));
            u.setRol(Rol.ADMIN);
            u.setActivo(true);
            usuarioRepository.save(u);
            if (administradorRepository.findByUsuario(u).isEmpty()) {
                administradorRepository.save(Administrador.builder()
                        .usuario(u).cargo("Gerencia General").build());
            }
        }, () -> {
            Usuario admin = usuarioRepository.save(Usuario.builder()
                    .nombre("Administrador")
                    .email("admin@hospy.com")
                    .password(passwordEncoder.encode("admin123"))
                    .rol(Rol.ADMIN)
                    .activo(true)
                    .build());
            administradorRepository.save(Administrador.builder()
                    .usuario(admin)
                    .cargo("Gerencia General")
                    .build());
        });
    }

    private void asegurarMedicoDemo() {
        UsuarioRequest req = new UsuarioRequest();
        req.setNombre("Dr. María García");
        req.setEmail("doctor@hospy.com");
        req.setPassword("Medico123");
        req.setRol("MEDICO");
        req.setEspecialidad("Cardiología");
        req.setNumeroLicencia("MED-2024-001");
        req.setConsultorio("Consultorio 301");
        req.setAnosExperiencia(12);
        req.setBiografia("Especialista en cardiología clínica.");
        req.setTelefono("3001112233");

        usuarioRepository.findByEmail("doctor@hospy.com").ifPresentOrElse(u -> {
            u.setPassword(passwordEncoder.encode("Medico123"));
            u.setRol(Rol.MEDICO);
            u.setActivo(true);
            u.setNombre(req.getNombre());
            u.setTelefono(req.getTelefono());
            usuarioRepository.save(u);
            if (medicoRepository.findByUsuario(u).isEmpty()) {
                perfilService.crearPerfil(u, req);
            }
        }, () -> {
            Usuario u = usuarioRepository.save(Usuario.builder()
                    .nombre(req.getNombre()).email(req.getEmail())
                    .password(passwordEncoder.encode(req.getPassword()))
                    .rol(Rol.MEDICO).telefono(req.getTelefono()).activo(true).build());
            perfilService.crearPerfil(u, req);
        });
    }

    /** Migra usuarios viejos que no tenían tabla de perfil */
    private void migrarPerfilesExistentes() {
        usuarioRepository.findAll().forEach(u -> {
            UsuarioRequest req = new UsuarioRequest();
            req.setDocumento(null);
            req.setEspecialidad("General");
            req.setCargo("Administrador");
            switch (u.getRol()) {
                case PACIENTE -> {
                    if (pacienteRepository.findByUsuario(u).isEmpty()) {
                        RegisterRequest r = new RegisterRequest();
                        r.setDocumento("Migrado");
                        perfilService.crearPaciente(u, r);
                    }
                }
                case MEDICO -> {
                    if (medicoRepository.findByUsuario(u).isEmpty()) {
                        perfilService.crearPerfil(u, req);
                    }
                }
                case ADMIN -> {
                    if (administradorRepository.findByUsuario(u).isEmpty()) {
                        perfilService.crearPerfil(u, req);
                    }
                }
            }
        });
    }
}
