package com.hospy.controller;

import com.hospy.dto.ApiMessage;
import com.hospy.dto.AuthRequest;
import com.hospy.dto.AuthResponse;
import com.hospy.dto.RegisterRequest;
import com.hospy.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<ApiMessage> registrar(@Valid @RequestBody RegisterRequest request) {
        authService.registrar(request);
        return ResponseEntity.ok(new ApiMessage(
                "Registro exitoso. Inicie sesión en el portal de pacientes."));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody AuthRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }
}
