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
        return ResponseEntity.ok(medicoPerfilService.dashboard(currentEmail()));
    }

    @GetMapping("/profile")
    public ResponseEntity<DoctorProfileDto> profile() {
        return ResponseEntity.ok(medicoPerfilService.loadProfile(currentEmail()));
    }

    @GetMapping("/appointments")
    public ResponseEntity<List<AppointmentResponse>> ownAppointments() {
        return ResponseEntity.ok(citaService.appointmentsForDoctor(currentEmail()));
    }

    @PatchMapping("/appointments/{id}/accept")
    public ResponseEntity<AppointmentResponse> accept(@PathVariable Long id) {
        return ResponseEntity.ok(citaService.accept(id, currentEmail()));
    }

    @PatchMapping("/appointments/{id}/reject")
    public ResponseEntity<AppointmentResponse> reject(@PathVariable Long id) {
        return ResponseEntity.ok(citaService.reject(id, currentEmail()));
    }

    @GetMapping("/appointments/{id}/patient")
    public ResponseEntity<PatientProfileDto> viewPatient(@PathVariable Long id) {
        return ResponseEntity.ok(citaService.patientForAppointment(id, currentEmail()));
    }

    @GetMapping("/appointments/{id}/patient-history")
    public ResponseEntity<List<AppointmentResponse>> patientHistory(@PathVariable Long id) {
        return ResponseEntity.ok(citaService.historyForDoctorVisit(id, currentEmail()));
    }

    @PostMapping("/appointments/{id}/indicacion")
    public ResponseEntity<ApiMessage> sendPrescription(@PathVariable Long id, @RequestBody PrescriptionRequest req) {
        boolean correo = citaService.sendPrescription(id, currentEmail(), req);
        String aviso = correo
                ? "Consulta concluida. Le llegó a la campana y al correo. El administrador también la ve."
                : "Consulta concluida. Le llegó a la campana. El correo no salió; el administrador la ve en Citas.";
        return ResponseEntity.ok(new ApiMessage(aviso));
    }

    @GetMapping("/schedules")
    public ResponseEntity<List<ScheduleResponse>> ownSchedules() {
        return ResponseEntity.ok(horarioService.ownSchedules(currentEmail()));
    }

    @PostMapping("/schedules")
    public ResponseEntity<ScheduleResponse> createSchedule(@Valid @RequestBody ScheduleRequest request) {
        return ResponseEntity.ok(horarioService.create(currentEmail(), request));
    }

    @PutMapping("/schedules/{id}")
    public ResponseEntity<ScheduleResponse> updateSchedule(
            @PathVariable Long id, @Valid @RequestBody ScheduleRequest request) {
        return ResponseEntity.ok(horarioService.update(id, currentEmail(), request));
    }

    @DeleteMapping("/schedules/{id}")
    public ResponseEntity<ApiMessage> deleteSchedule(@PathVariable Long id) {
        horarioService.remove(id, currentEmail());
        return ResponseEntity.ok(new ApiMessage("Schedule eliminado"));
    }

    private String currentEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
