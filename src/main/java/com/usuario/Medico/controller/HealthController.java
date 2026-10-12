package com.usuario.Medico.controller;

import com.usuario.Medico.service.AiService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class HealthController {

    private final AiService aiService;

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of(
                "status", "ok",
                "app", "Sistema Hospy",
                "gemini", aiService.hayGemini() ? "activo" : "sin_clave"
        );
    }
}
