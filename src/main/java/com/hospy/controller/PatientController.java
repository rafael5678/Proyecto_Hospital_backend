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
        return ResponseEntity.ok(pacienteService.dashboard(currentEmail()));
    }

    @GetMapping("/profile")
    public ResponseEntity<PatientProfileDto> profile() {
        return ResponseEntity.ok(pacienteService.loadProfile(currentEmail()));
    }

    @PutMapping("/profile")
    public ResponseEntity<PatientProfileDto> updateProfile(@RequestBody PatientProfileDto dto) {
        return ResponseEntity.ok(pacienteService.updateProfile(currentEmail(), dto));
    }

    @PostMapping("/appointments")
    public ResponseEntity<AppointmentResponse> book(@Valid @RequestBody AppointmentRequest request) {
        return ResponseEntity.ok(citaService.book(currentEmail(), request));
    }

    @GetMapping("/appointments/history")
    public ResponseEntity<List<AppointmentResponse>> historial() {
        return ResponseEntity.ok(citaService.patientHistory(currentEmail()));
    }

    @GetMapping("/appointments/upcoming")
    public ResponseEntity<List<AppointmentResponse>> upcoming() {
        return ResponseEntity.ok(citaService.proximasCitas(currentEmail()));
    }

    @PatchMapping("/appointments/{id}/cancel")
    public ResponseEntity<AppointmentResponse> cancel(@PathVariable Long id) {
        return ResponseEntity.ok(citaService.cancel(id, currentEmail()));
    }

    private String currentEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
