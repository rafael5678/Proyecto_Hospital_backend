package com.hospy.config;

import com.hospy.dto.RegisterRequest;
import com.hospy.dto.UserRequest;
import com.hospy.model.Administrator;
import com.hospy.model.Role;
import com.hospy.model.User;
import com.hospy.repository.AdministratorRepository;
import com.hospy.repository.DoctorRepository;
import com.hospy.repository.PatientRepository;
import com.hospy.repository.UserRepository;
import com.hospy.service.ScheduleService;
import com.hospy.service.NotificationInboxService;
import com.hospy.service.ProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository usuarioRepository;
    private final AdministratorRepository administradorRepository;
    private final PatientRepository pacienteRepository;
    private final DoctorRepository medicoRepository;
    private final PasswordEncoder passwordEncoder;
    private final ProfileService perfilService;
    private final ScheduleService horarioService;
    private final NotificationInboxService notificacionInboxService;

    @Override
    @Transactional
    public void run(String... args) {
        asegurarAdminDemo();
        migrarPerfilesExistentes();
        asegurarMedicoDemo();
        asegurarMedico("neurologia@hospy.com", "Dra. Ana López", "Neurología", "MED-2024-002");
        asegurarMedico("dermatologia@hospy.com", "Dr. Carlos Pérez", "Dermatología", "MED-2024-003");
        asegurarMedico("traumatologia@hospy.com", "Dr. Andrés Molina", "Traumatología", "MED-2024-004");
        asegurarMedico("general@hospy.com", "Dr. Luis Herrera", "Medicina General", "MED-2024-005");
        medicoRepository.findAll().forEach(horarioService::asegurarHorarioLaboral);
        asegurarPacienteDemo("pedro@hospy.com", "Pedro Patient", "123456");
        asegurarPacienteDemo("paciente@hospy.com", "Juan Reyes", "Paciente123");
        notificacionInboxService.avisarAceptadasSinAviso();
    }

    private void asegurarPacienteDemo(String email, String nombre, String clave) {
        usuarioRepository.findByEmail(email).ifPresentOrElse(u -> {
            u.setPassword(passwordEncoder.encode(clave));
            u.setRol(Role.PACIENTE);
            u.setActivo(true);
            u.setNombre(nombre);
            usuarioRepository.save(u);
            if (pacienteRepository.findByUsuario(u).isEmpty()) {
                RegisterRequest r = new RegisterRequest();
                r.setDocumento("CC-DEMO");
                perfilService.crearPaciente(u, r);
            }
        }, () -> {
            User u = usuarioRepository.save(User.builder()
                    .nombre(nombre).email(email)
                    .password(passwordEncoder.encode(clave))
                    .rol(Role.PACIENTE).activo(true).build());
            RegisterRequest r = new RegisterRequest();
            r.setDocumento("CC-DEMO");
            perfilService.crearPaciente(u, r);
        });
    }

    private void asegurarMedico(String email, String nombre, String especialidad, String licencia) {
        UserRequest req = new UserRequest();
        req.setNombre(nombre);
        req.setEmail(email);
        req.setPassword("Medico123");
        req.setRol("MEDICO");
        req.setEspecialidad(especialidad);
        req.setNumeroLicencia(licencia);
        usuarioRepository.findByEmail(email).ifPresentOrElse(u -> {
            u.setNombre(nombre);
            u.setRol(Role.MEDICO);
            u.setActivo(true);
            usuarioRepository.save(u);
            medicoRepository.findByUsuario(u).ifPresentOrElse(m -> {
                m.setEspecialidad(especialidad);
                medicoRepository.save(m);
                horarioService.asegurarHorarioLaboral(m);
            }, () -> {
                perfilService.crearPerfil(u, req);
                medicoRepository.findByUsuario(u).ifPresent(horarioService::asegurarHorarioLaboral);
            });
        }, () -> {
            User u = usuarioRepository.save(User.builder()
                    .nombre(nombre).email(email)
                    .password(passwordEncoder.encode("Medico123"))
                    .rol(Role.MEDICO).activo(true).build());
            perfilService.crearPerfil(u, req);
            medicoRepository.findByUsuario(u).ifPresent(horarioService::asegurarHorarioLaboral);
        });
    }

    /** Credenciales fijas de demo (se reactivan y restablecen al arrancar). */
    private void asegurarAdminDemo() {
        usuarioRepository.findByEmail("admin@hospy.com").ifPresentOrElse(u -> {
            u.setPassword(passwordEncoder.encode("admin123"));
            u.setRol(Role.ADMIN);
            u.setActivo(true);
            usuarioRepository.save(u);
            if (administradorRepository.findByUsuario(u).isEmpty()) {
                administradorRepository.save(Administrator.builder()
                        .usuario(u).cargo("Gerencia General").build());
            }
        }, () -> {
            User admin = usuarioRepository.save(User.builder()
                    .nombre("Administrator")
                    .email("admin@hospy.com")
                    .password(passwordEncoder.encode("admin123"))
                    .rol(Role.ADMIN)
                    .activo(true)
                    .build());
            administradorRepository.save(Administrator.builder()
                    .usuario(admin)
                    .cargo("Gerencia General")
                    .build());
        });
    }

    private void asegurarMedicoDemo() {
        UserRequest req = new UserRequest();
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
            u.setRol(Role.MEDICO);
            u.setActivo(true);
            u.setNombre(req.getNombre());
            u.setTelefono(req.getTelefono());
            usuarioRepository.save(u);
            if (medicoRepository.findByUsuario(u).isEmpty()) {
                perfilService.crearPerfil(u, req);
            }
        }, () -> {
            User u = usuarioRepository.save(User.builder()
                    .nombre(req.getNombre()).email(req.getEmail())
                    .password(passwordEncoder.encode(req.getPassword()))
                    .rol(Role.MEDICO).telefono(req.getTelefono()).activo(true).build());
            perfilService.crearPerfil(u, req);
        });
    }

    /** Migra usuarios viejos que no tenían tabla de perfil */
    private void migrarPerfilesExistentes() {
        usuarioRepository.findAll().forEach(u -> {
            UserRequest req = new UserRequest();
            req.setDocumento(null);
            req.setEspecialidad("General");
            req.setCargo("Administrator");
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
