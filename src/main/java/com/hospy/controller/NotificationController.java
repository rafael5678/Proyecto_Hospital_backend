package com.hospy.controller;

import com.hospy.dto.NotificationResponse;
import com.hospy.service.NotificationInboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class NotificationController {

    private final NotificationInboxService inbox;

    @GetMapping
    public ResponseEntity<List<NotificationResponse>> listar() {
        return ResponseEntity.ok(inbox.listar(emailActual()));
    }

    @GetMapping("/unread")
    public ResponseEntity<Map<String, Long>> noLeidas() {
        return ResponseEntity.ok(Map.of("total", inbox.noLeidas(emailActual())));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<Void> leer(@PathVariable Long id) {
        inbox.marcarLeida(id, emailActual());
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/read-all")
    public ResponseEntity<Void> leerTodas() {
        inbox.marcarTodas(emailActual());
        return ResponseEntity.ok().build();
    }

    private String emailActual() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
