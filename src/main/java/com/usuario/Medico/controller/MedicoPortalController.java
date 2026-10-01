package com.usuario.Medico.controller;

import com.usuario.Medico.dto.*;
import com.usuario.Medico.service.CitaService;
import com.usuario.Medico.service.HorarioService;
import com.usuario.Medico.service.MedicoPerfilService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/medico")
@RequiredArgsConstructor
@PreAuthorize("hasRole('MEDICO')")
public class MedicoPortalController {

    private final CitaService citaService;
    private final HorarioService horarioService;
    private final MedicoPerfilService medicoPerfilService;

    @GetMapping("/dashboard")
    public ResponseEntity<MedicoDashboardDTO> dashboard() {
        return ResponseEntity.ok(medicoPerfilService.dashboard(emailActual()));
    }

    @GetMapping("/perfil")
    public ResponseEntity<MedicoPerfilDTO> perfil() {
        return ResponseEntity.ok(medicoPerfilService.obtenerPerfil(emailActual()));
    }

    @GetMapping("/citas")
    public ResponseEntity<List<CitaResponse>> misCitas() {
        return ResponseEntity.ok(citaService.citasMedico(emailActual()));
    }

    @PatchMapping("/citas/{id}/aceptar")
    public ResponseEntity<CitaResponse> aceptar(@PathVariable Long id) {
        return ResponseEntity.ok(citaService.aceptar(id, emailActual()));
    }

    @PatchMapping("/citas/{id}/rechazar")
    public ResponseEntity<CitaResponse> rechazar(@PathVariable Long id) {
        return ResponseEntity.ok(citaService.rechazar(id, emailActual()));
    }

    @GetMapping("/citas/{id}/paciente")
    public ResponseEntity<PacientePerfilDTO> verPaciente(@PathVariable Long id) {
        return ResponseEntity.ok(citaService.pacienteDeCita(id, emailActual()));
    }

    @GetMapping("/citas/{id}/historial-paciente")
    public ResponseEntity<List<CitaResponse>> historialPaciente(@PathVariable Long id) {
        return ResponseEntity.ok(citaService.historialPacienteDeCita(id, emailActual()));
    }

    @GetMapping("/horarios")
    public ResponseEntity<List<HorarioResponse>> misHorarios() {
        return ResponseEntity.ok(horarioService.misHorarios(emailActual()));
    }

    @PostMapping("/horarios")
    public ResponseEntity<HorarioResponse> crearHorario(@Valid @RequestBody HorarioRequest request) {
        return ResponseEntity.ok(horarioService.crear(emailActual(), request));
    }

    @PutMapping("/horarios/{id}")
    public ResponseEntity<HorarioResponse> actualizarHorario(
            @PathVariable Long id, @Valid @RequestBody HorarioRequest request) {
        return ResponseEntity.ok(horarioService.actualizar(id, emailActual(), request));
    }

    @DeleteMapping("/horarios/{id}")
    public ResponseEntity<ApiMessage> eliminarHorario(@PathVariable Long id) {
        horarioService.eliminar(id, emailActual());
        return ResponseEntity.ok(new ApiMessage("Horario eliminado"));
    }

    private String emailActual() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
