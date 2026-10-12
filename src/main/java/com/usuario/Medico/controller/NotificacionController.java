package com.usuario.Medico.controller;

import com.usuario.Medico.dto.NotificacionResponse;
import com.usuario.Medico.service.NotificacionInboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notificaciones")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class NotificacionController {

    private final NotificacionInboxService inbox;

    @GetMapping
    public ResponseEntity<List<NotificacionResponse>> listar() {
        return ResponseEntity.ok(inbox.listar(emailActual()));
    }

    @GetMapping("/no-leidas")
    public ResponseEntity<Map<String, Long>> noLeidas() {
        return ResponseEntity.ok(Map.of("total", inbox.noLeidas(emailActual())));
    }

    @PatchMapping("/{id}/leer")
    public ResponseEntity<Void> leer(@PathVariable Long id) {
        inbox.marcarLeida(id, emailActual());
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/leer-todas")
    public ResponseEntity<Void> leerTodas() {
        inbox.marcarTodas(emailActual());
        return ResponseEntity.ok().build();
    }

    private String emailActual() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
