package com.usuario.Medico.controller;

import com.usuario.Medico.dto.*;
import com.usuario.Medico.service.AiService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiService aiService;

    @GetMapping("/status")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AiStatusResponse> status() {
        return ResponseEntity.ok(new AiStatusResponse(
                aiService.hayApiKey(),
                "Hospy AI — asistente de lenguaje y transcripción Whisper; requiere revisión profesional"
        ));
    }

    /* Pacientes y médicos pueden triagear */
    @PostMapping("/triage")
    @PreAuthorize("hasAnyRole('PACIENTE','MEDICO','ADMIN')")
    public ResponseEntity<AiTriageResponse> triage(@Valid @RequestBody AiTriageRequest req) {
        return ResponseEntity.ok(aiService.triage(req));
    }

    /* Pacientes y médicos pueden evaluar lesiones */
    @PostMapping("/dermatologia")
    @PreAuthorize("hasAnyRole('PACIENTE','MEDICO','ADMIN')")
    public ResponseEntity<AiDermatologiaResponse> dermatologia(@Valid @RequestBody AiDermatologiaRequest req) {
        return ResponseEntity.ok(aiService.dermatologia(req));
    }

    /* Solo médicos y admins pueden usar el escriba SOAP clínico */
    @PostMapping("/soap")
    @PreAuthorize("hasAnyRole('MEDICO','ADMIN')")
    public ResponseEntity<AiSoapResponse> soap(@Valid @RequestBody AiSoapRequest req) {
        return ResponseEntity.ok(aiService.soap(req));
    }

    @PostMapping(value = "/transcribir", consumes = "multipart/form-data")
    @PreAuthorize("hasAnyRole('MEDICO','ADMIN')")
    public ResponseEntity<AiTranscripcionResponse> transcribir(@RequestParam("audio") MultipartFile audio) throws IOException {
        if (audio.isEmpty()) return ResponseEntity.badRequest().build();
        if (audio.getSize() > 10 * 1024 * 1024) return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        String tipo = audio.getContentType() == null ? "" : audio.getContentType().toLowerCase();
        if (!tipo.startsWith("audio/webm") && !tipo.startsWith("audio/mp4") &&
                !tipo.startsWith("audio/wav") && !tipo.startsWith("audio/ogg") &&
                !tipo.startsWith("audio/mpeg")) {
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).build();
        }
        return ResponseEntity.ok(aiService.transcribir(audio.getBytes(), tipo));
    }

    /* Pacientes, médicos y admins pueden revisar interacciones */
    @PostMapping("/interacciones")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AiInteraccionResponse> interacciones(@Valid @RequestBody AiInteraccionRequest req) {
        return ResponseEntity.ok(aiService.interacciones(req));
    }

    /* Todos (autenticados) pueden triangular precios */
    @PostMapping("/precios")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AiPreciosResponse> precios(@Valid @RequestBody AiPreciosRequest req) {
        return ResponseEntity.ok(aiService.precios(req));
    }

    public record AiStatusResponse(boolean apiKeyActiva, String version) { }
}
