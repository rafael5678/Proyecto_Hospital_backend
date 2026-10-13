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
    public ResponseEntity<AiDermatologyResponse> dermatologia(@Valid @RequestBody AiDermatologyRequest req) {
        return ResponseEntity.ok(aiService.dermatologia(req));
    }

    /* Solo médicos y admins pueden usar el escriba SOAP clínico */
    @PostMapping("/soap")
    @PreAuthorize("hasAnyRole('MEDICO','ADMIN')")
    public ResponseEntity<AiSoapResponse> soap(@Valid @RequestBody AiSoapRequest req) {
        return ResponseEntity.ok(aiService.soap(req));
    }

    @PostMapping(value = "/transcribe", consumes = "multipart/form-data")
    @PreAuthorize("hasAnyRole('MEDICO','ADMIN')")
    public ResponseEntity<AiTranscriptionResponse> transcribir(@RequestParam("audio") MultipartFile audio) throws IOException {
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
    @PostMapping("/interactions")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AiInteractionResponse> interacciones(@Valid @RequestBody AiInteractionRequest req) {
        return ResponseEntity.ok(aiService.interacciones(req));
    }

    @PostMapping("/admin-operacion")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AiAdminOperationResponse> adminOperacion(@Valid @RequestBody AiAdminOperationRequest req) {
        return ResponseEntity.ok(aiService.adminOperacion(req));
    }

    /* Todos (autenticados) pueden triangular precios */
    @PostMapping("/prices")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AiPricesResponse> precios(@Valid @RequestBody AiPricesRequest req) {
        return ResponseEntity.ok(aiService.precios(req));
    }

    public record AiStatusResponse(boolean apiKeyActiva, String version) { }
}
