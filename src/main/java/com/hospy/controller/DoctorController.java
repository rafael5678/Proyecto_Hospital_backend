package com.hospy.controller;

import com.hospy.dto.SlotResponse;
import com.hospy.dto.ScheduleResponse;
import com.hospy.dto.UserDto;
import com.hospy.service.AppointmentService;
import com.hospy.service.ScheduleService;
import com.hospy.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** HTTP API for doctor directory and files. */
@RestController
@RequestMapping("/api/doctors")
@RequiredArgsConstructor
public class DoctorController {

    private final UserService usuarioService;
    private final ScheduleService horarioService;
    private final AppointmentService citaService;

    @GetMapping
    public ResponseEntity<List<UserDto>> listDoctors() {
        return ResponseEntity.ok(usuarioService.listDoctors());
    }

    @GetMapping("/{id}/schedules")
    public ResponseEntity<List<ScheduleResponse>> schedules(@PathVariable Long id) {
        return ResponseEntity.ok(horarioService.listByDoctor(id));
    }

    @GetMapping("/{id}/slots")
    public ResponseEntity<List<SlotResponse>> slots(
            @PathVariable Long id,
            @RequestParam(defaultValue = "12") int limite) {
        return ResponseEntity.ok(citaService.listSlots(id, limite));
    }
}
