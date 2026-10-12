package com.usuario.Medico.controller;

import com.usuario.Medico.dto.ApiMessage;
import com.usuario.Medico.dto.AuthRequest;
import com.usuario.Medico.dto.AuthResponse;
import com.usuario.Medico.dto.RegisterRequest;
import com.usuario.Medico.service.AuthService;
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
