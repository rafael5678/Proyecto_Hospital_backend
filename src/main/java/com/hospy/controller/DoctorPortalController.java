package com.hospy.controller;

import com.hospy.dto.*;
import com.hospy.service.AppointmentService;
import com.hospy.service.ScheduleService;
import com.hospy.service.DoctorProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/doctor")
@RequiredArgsConstructor
@PreAuthorize("hasRole('MEDICO')")
public class DoctorPortalController {

    private final AppointmentService citaService;
    private final ScheduleService horarioService;
    private final DoctorProfileService medicoPerfilService;

    @GetMapping("/dashboard")
    public ResponseEntity<DoctorDashboardDto> dashboard() {
        return ResponseEntity.ok(medicoPerfilService.dashboard(emailActual()));
    }

    @GetMapping("/profile")
    public ResponseEntity<DoctorProfileDto> perfil() {
        return ResponseEntity.ok(medicoPerfilService.obtenerPerfil(emailActual()));
    }

    @GetMapping("/appointments")
    public ResponseEntity<List<AppointmentResponse>> misCitas() {
        return ResponseEntity.ok(citaService.citasMedico(emailActual()));
    }

    @PatchMapping("/appointments/{id}/accept")
    public ResponseEntity<AppointmentResponse> aceptar(@PathVariable Long id) {
        return ResponseEntity.ok(citaService.aceptar(id, emailActual()));
    }

    @PatchMapping("/appointments/{id}/reject")
    public ResponseEntity<AppointmentResponse> rechazar(@PathVariable Long id) {
        return ResponseEntity.ok(citaService.rechazar(id, emailActual()));
    }

    @GetMapping("/appointments/{id}/patient")
    public ResponseEntity<PatientProfileDto> verPaciente(@PathVariable Long id) {
        return ResponseEntity.ok(citaService.pacienteDeCita(id, emailActual()));
    }

    @GetMapping("/appointments/{id}/patient-history")
    public ResponseEntity<List<AppointmentResponse>> historialPaciente(@PathVariable Long id) {
        return ResponseEntity.ok(citaService.historialPacienteDeCita(id, emailActual()));
    }

    @GetMapping("/schedules")
    public ResponseEntity<List<ScheduleResponse>> misHorarios() {
        return ResponseEntity.ok(horarioService.misHorarios(emailActual()));
    }

    @PostMapping("/schedules")
    public ResponseEntity<ScheduleResponse> crearHorario(@Valid @RequestBody ScheduleRequest request) {
        return ResponseEntity.ok(horarioService.crear(emailActual(), request));
    }

    @PutMapping("/schedules/{id}")
    public ResponseEntity<ScheduleResponse> actualizarHorario(
            @PathVariable Long id, @Valid @RequestBody ScheduleRequest request) {
        return ResponseEntity.ok(horarioService.actualizar(id, emailActual(), request));
    }

    @DeleteMapping("/schedules/{id}")
    public ResponseEntity<ApiMessage> eliminarHorario(@PathVariable Long id) {
        horarioService.eliminar(id, emailActual());
        return ResponseEntity.ok(new ApiMessage("Schedule eliminado"));
    }

    private String emailActual() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
