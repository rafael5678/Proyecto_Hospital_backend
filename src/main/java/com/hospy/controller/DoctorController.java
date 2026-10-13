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

@RestController
@RequestMapping("/api/doctors")
@RequiredArgsConstructor
public class DoctorController {

    private final UserService usuarioService;
    private final ScheduleService horarioService;
    private final AppointmentService citaService;

    @GetMapping
    public ResponseEntity<List<UserDto>> listarMedicos() {
        return ResponseEntity.ok(usuarioService.listarMedicos());
    }

    @GetMapping("/{id}/schedules")
    public ResponseEntity<List<ScheduleResponse>> horarios(@PathVariable Long id) {
        return ResponseEntity.ok(horarioService.listarPorMedico(id));
    }

    @GetMapping("/{id}/slots")
    public ResponseEntity<List<SlotResponse>> cupos(
            @PathVariable Long id,
            @RequestParam(defaultValue = "12") int limite) {
        return ResponseEntity.ok(citaService.listarCupos(id, limite));
    }
}
