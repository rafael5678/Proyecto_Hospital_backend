package com.usuario.Medico.controller;

import com.usuario.Medico.dto.*;
import com.usuario.Medico.service.CitaService;
import com.usuario.Medico.service.PacienteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/paciente")
@RequiredArgsConstructor
@PreAuthorize("hasRole('PACIENTE')")
public class PacienteController {

    private final CitaService citaService;
    private final PacienteService pacienteService;

    @GetMapping("/dashboard")
    public ResponseEntity<PacienteDashboardDTO> dashboard() {
        return ResponseEntity.ok(pacienteService.dashboard(emailActual()));
    }

    @GetMapping("/perfil")
    public ResponseEntity<PacientePerfilDTO> perfil() {
        return ResponseEntity.ok(pacienteService.obtenerPerfil(emailActual()));
    }

    @PutMapping("/perfil")
    public ResponseEntity<PacientePerfilDTO> actualizarPerfil(@RequestBody PacientePerfilDTO dto) {
        return ResponseEntity.ok(pacienteService.actualizarPerfil(emailActual(), dto));
    }

    @PostMapping("/citas")
    public ResponseEntity<CitaResponse> agendar(@Valid @RequestBody CitaRequest request) {
        return ResponseEntity.ok(citaService.agendar(emailActual(), request));
    }

    @GetMapping("/citas/historial")
    public ResponseEntity<List<CitaResponse>> historial() {
        return ResponseEntity.ok(citaService.historialPaciente(emailActual()));
    }

    @GetMapping("/citas/proximas")
    public ResponseEntity<List<CitaResponse>> proximas() {
        return ResponseEntity.ok(citaService.proximasCitas(emailActual()));
    }

    @PatchMapping("/citas/{id}/cancelar")
    public ResponseEntity<CitaResponse> cancelar(@PathVariable Long id) {
        return ResponseEntity.ok(citaService.cancelar(id, emailActual()));
    }

    private String emailActual() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
