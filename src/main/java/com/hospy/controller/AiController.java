package com.hospy.controller;

import com.hospy.dto.*;
import com.hospy.service.AiService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/** HTTP API for triage, consult, pharmacy, and SOAP. */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiService aiService;

    @GetMapping("/status")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AiStatusResponse> status() {
        return ResponseEntity.ok(new AiStatusResponse(
                aiService.hasApiKey(),
                aiService.versionIa() + "; requiere revisión profesional"
        ));
    }

    /* Pacientes y médicos pueden triagear */
    @PostMapping("/triage")
    @PreAuthorize("hasAnyRole('PACIENTE','MEDICO','ADMIN')")
    public ResponseEntity<AiTriageResponse> triage(@Valid @RequestBody AiTriageRequest req) {
        return ResponseEntity.ok(aiService.triage(req));
    }

    /* Pacientes y médicos pueden evaluar lesiones */
    @PostMapping("/dermatology")
    @PreAuthorize("hasAnyRole('PACIENTE','MEDICO','ADMIN')")
    public ResponseEntity<AiDermatologyResponse> dermatology(@Valid @RequestBody AiDermatologyRequest req) {
        return ResponseEntity.ok(aiService.dermatology(req));
    }

    /* Solo médicos y admins pueden usar el escriba SOAP clínico */
    @PostMapping("/soap")
    @PreAuthorize("hasAnyRole('MEDICO','ADMIN')")
    public ResponseEntity<AiSoapResponse> soap(@Valid @RequestBody AiSoapRequest req) {
        return ResponseEntity.ok(aiService.soap(req));
    }

    @PostMapping("/consulta")
    @PreAuthorize("hasAnyRole('MEDICO','ADMIN')")
    public ResponseEntity<AiConsultaSimResponse> consult(@RequestBody AiConsultaSimRequest req) {
        return ResponseEntity.ok(aiService.consultaSimulada(req));
    }

    @PostMapping("/warmup")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AiWarmupResponse> warmup() {
        return ResponseEntity.ok(new AiWarmupResponse(aiService.warmupConsult()));
    }

    @PostMapping(value = "/transcribe", consumes = "multipart/form-data")
    @PreAuthorize("hasAnyRole('MEDICO','ADMIN')")
    public ResponseEntity<AiTranscriptionResponse> transcribe(@RequestParam("audio") MultipartFile audio) throws IOException {
        if (audio.isEmpty()) return ResponseEntity.badRequest().build();
        if (audio.getSize() > 10 * 1024 * 1024) return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        String tipo = audio.getContentType() == null ? "" : audio.getContentType().toLowerCase();
        if (!tipo.startsWith("audio/webm") && !tipo.startsWith("audio/mp4") &&
                !tipo.startsWith("audio/wav") && !tipo.startsWith("audio/ogg") &&
                !tipo.startsWith("audio/mpeg")) {
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).build();
        }
        return ResponseEntity.ok(aiService.transcribe(audio.getBytes(), tipo));
    }

    /* Pacientes, médicos y admins pueden revisar interacciones */
    @PostMapping("/interactions")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AiInteractionResponse> interactions(@Valid @RequestBody AiInteractionRequest req) {
        return ResponseEntity.ok(aiService.interactions(req));
    }

    @PostMapping("/admin-operacion")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AiAdminOperationResponse> adminOperation(@Valid @RequestBody AiAdminOperationRequest req) {
        return ResponseEntity.ok(aiService.adminOperation(req));
    }

    /* Todos (autenticados) pueden triangular precios */
    @PostMapping("/prices")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AiPricesResponse> prices(@Valid @RequestBody AiPricesRequest req) {
        return ResponseEntity.ok(aiService.prices(req));
    }

    public record AiStatusResponse(boolean apiKeyActiva, String version) { }

    public record AiWarmupResponse(boolean ok) { }
}
