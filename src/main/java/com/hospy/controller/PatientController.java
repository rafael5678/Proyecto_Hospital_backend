package com.hospy.controller;

import com.hospy.dto.*;
import com.hospy.service.AppointmentService;
import com.hospy.service.PatientService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/patient")
@RequiredArgsConstructor
@PreAuthorize("hasRole('PACIENTE')")
public class PatientController {

    private final AppointmentService citaService;
    private final PatientService pacienteService;

    @GetMapping("/dashboard")
    public ResponseEntity<PatientDashboardDto> dashboard() {
        return ResponseEntity.ok(pacienteService.dashboard(emailActual()));
    }

    @GetMapping("/profile")
    public ResponseEntity<PatientProfileDto> perfil() {
        return ResponseEntity.ok(pacienteService.obtenerPerfil(emailActual()));
    }

    @PutMapping("/profile")
    public ResponseEntity<PatientProfileDto> actualizarPerfil(@RequestBody PatientProfileDto dto) {
        return ResponseEntity.ok(pacienteService.actualizarPerfil(emailActual(), dto));
    }

    @PostMapping("/appointments")
    public ResponseEntity<AppointmentResponse> agendar(@Valid @RequestBody AppointmentRequest request) {
        return ResponseEntity.ok(citaService.agendar(emailActual(), request));
    }

    @GetMapping("/appointments/history")
    public ResponseEntity<List<AppointmentResponse>> historial() {
        return ResponseEntity.ok(citaService.historialPaciente(emailActual()));
    }

    @GetMapping("/appointments/upcoming")
    public ResponseEntity<List<AppointmentResponse>> proximas() {
        return ResponseEntity.ok(citaService.proximasCitas(emailActual()));
    }

    @PatchMapping("/appointments/{id}/cancel")
    public ResponseEntity<AppointmentResponse> cancelar(@PathVariable Long id) {
        return ResponseEntity.ok(citaService.cancelar(id, emailActual()));
    }

    private String emailActual() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
