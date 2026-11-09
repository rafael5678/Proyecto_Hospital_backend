package com.hospy.controller;

import com.hospy.dto.*;
import com.hospy.service.AiService;
import com.hospy.service.AppointmentService;
import com.hospy.service.ReportService;
import com.hospy.service.SpecialtyService;
import com.hospy.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final UserService usuarioService;
    private final AppointmentService citaService;
    private final ReportService reporteService;
    private final AiService aiService;
    private final SpecialtyService especialidadService;

    @GetMapping("/users")
    public ResponseEntity<List<UserDto>> listUsers() {
        return ResponseEntity.ok(usuarioService.listAll());
    }

    @PostMapping("/users")
    public ResponseEntity<UserDto> createUser(@Valid @RequestBody UserRequest request) {
        request.setRol("PACIENTE");
        return ResponseEntity.ok(usuarioService.create(request));
    }

    @PutMapping("/users/{id}")
    public ResponseEntity<UserDto> updateUser(
            @PathVariable Long id, @Valid @RequestBody UserRequest request) {
        return ResponseEntity.ok(usuarioService.update(id, request));
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<ApiMessage> deactivateUser(@PathVariable Long id) {
        usuarioService.remove(id);
        return ResponseEntity.ok(new ApiMessage("User desactivado"));
    }

    @PatchMapping("/users/{id}/estado")
    public ResponseEntity<UserDto> changeActive(@PathVariable Long id, @RequestParam boolean activo) {
        return ResponseEntity.ok(usuarioService.changeActive(id, activo));
    }

    @PatchMapping("/users/{id}/password")
    public ResponseEntity<ApiMessage> resetPassword(
            @PathVariable Long id,
            @RequestBody java.util.Map<String, String> body,
            org.springframework.security.core.Authentication authentication) {
        usuarioService.resetPassword(
                id,
                body.get("nuevaClave"),
                body.get("claveAdmin"),
                authentication.getName());
        return ResponseEntity.ok(new ApiMessage("Clave restablecida"));
    }

    @PostMapping("/doctors")
    public ResponseEntity<UserDto> createDoctor(@Valid @RequestBody UserRequest request) {
        request.setRol("MEDICO");
        return ResponseEntity.ok(usuarioService.create(request));
    }

    @GetMapping("/doctors")
    public ResponseEntity<List<UserDto>> listDoctors() {
        return ResponseEntity.ok(usuarioService.listEveryDoctor());
    }

    @PutMapping("/doctors/{id}")
    public ResponseEntity<UserDto> updateDoctor(
            @PathVariable Long id, @Valid @RequestBody UserRequest request) {
        return ResponseEntity.ok(usuarioService.updateDoctor(id, request));
    }

    @GetMapping("/appointments")
    public ResponseEntity<List<AppointmentResponse>> superviseAppointments() {
        return ResponseEntity.ok(citaService.allAppointments());
    }

    @PatchMapping("/appointments/{id}/reschedule")
    public ResponseEntity<RescheduleAppointmentResponse> rescheduleAppointment(
            @PathVariable Long id, @Valid @RequestBody RescheduleAppointmentRequest request,
            org.springframework.security.core.Authentication authentication) {
        return ResponseEntity.ok(citaService.reschedule(id, request, authentication.getName()));
    }

    @GetMapping("/reports")
    public ResponseEntity<ReportResponse> reports(
            @RequestParam(defaultValue = "2026") int anio) {
        return ResponseEntity.ok(reporteService.buildReport(anio));
    }

    @GetMapping("/ai-metrics")
    public ResponseEntity<AiMetricsResponse> aiMetrics() {
        return ResponseEntity.ok(reporteService.buildAiMetrics(
                aiService.hasApiKey(), aiService.versionIa(), aiService.transcriptionStatus()));
    }

    @GetMapping("/specialties")
    public ResponseEntity<List<SpecialtyDto>> listSpecialties() {
        return ResponseEntity.ok(especialidadService.list());
    }

    @PostMapping("/specialties")
    public ResponseEntity<SpecialtyDto> createSpecialty(@RequestBody java.util.Map<String, String> body) {
        return ResponseEntity.ok(especialidadService.create(body.get("nombre")));
    }

    @PostMapping("/ai-ping")
    public ResponseEntity<java.util.Map<String, String>> pingAssistant() {
        return ResponseEntity.ok(aiService.pingConnection());
    }
}
