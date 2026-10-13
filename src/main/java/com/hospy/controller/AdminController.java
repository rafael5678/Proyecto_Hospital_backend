package com.hospy.controller;

import com.hospy.dto.*;
import com.hospy.service.AiService;
import com.hospy.service.AppointmentService;
import com.hospy.service.ReportService;
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

    @GetMapping("/users")
    public ResponseEntity<List<UserDto>> listarUsuarios() {
        return ResponseEntity.ok(usuarioService.listarTodos());
    }

    @PostMapping("/users")
    public ResponseEntity<UserDto> crearUsuario(@Valid @RequestBody UserRequest request) {
        request.setRol("PACIENTE");
        return ResponseEntity.ok(usuarioService.crear(request));
    }

    @PutMapping("/users/{id}")
    public ResponseEntity<UserDto> actualizarUsuario(
            @PathVariable Long id, @Valid @RequestBody UserRequest request) {
        return ResponseEntity.ok(usuarioService.actualizar(id, request));
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<ApiMessage> eliminarUsuario(@PathVariable Long id) {
        usuarioService.eliminar(id);
        return ResponseEntity.ok(new ApiMessage("User desactivado"));
    }

    @PatchMapping("/users/{id}/estado")
    public ResponseEntity<UserDto> cambiarEstado(@PathVariable Long id, @RequestParam boolean activo) {
        return ResponseEntity.ok(usuarioService.cambiarEstado(id, activo));
    }

    @PostMapping("/doctors")
    public ResponseEntity<UserDto> crearMedico(@Valid @RequestBody UserRequest request) {
        request.setRol("MEDICO");
        return ResponseEntity.ok(usuarioService.crear(request));
    }

    @GetMapping("/doctors")
    public ResponseEntity<List<UserDto>> listarMedicos() {
        return ResponseEntity.ok(usuarioService.listarTodosLosMedicos());
    }

    @PutMapping("/doctors/{id}")
    public ResponseEntity<UserDto> actualizarMedico(
            @PathVariable Long id, @Valid @RequestBody UserRequest request) {
        return ResponseEntity.ok(usuarioService.actualizarMedico(id, request));
    }

    @GetMapping("/appointments")
    public ResponseEntity<List<AppointmentResponse>> supervisarCitas() {
        return ResponseEntity.ok(citaService.todasLasCitas());
    }

    @PatchMapping("/appointments/{id}/reschedule")
    public ResponseEntity<RescheduleAppointmentResponse> reprogramarCita(
            @PathVariable Long id, @Valid @RequestBody RescheduleAppointmentRequest request,
            org.springframework.security.core.Authentication authentication) {
        return ResponseEntity.ok(citaService.reprogramar(id, request, authentication.getName()));
    }

    @GetMapping("/reports")
    public ResponseEntity<ReportResponse> reportes(
            @RequestParam(defaultValue = "2026") int anio) {
        return ResponseEntity.ok(reporteService.generarReporte(anio));
    }

    @GetMapping("/ai-metrics")
    public ResponseEntity<AiMetricsResponse> aiMetricas() {
        return ResponseEntity.ok(reporteService.generarMetricasIA(
                aiService.hayApiKey(), aiService.versionIa(), aiService.estadoTranscripcion()));
    }
}
