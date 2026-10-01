package com.usuario.Medico.controller;

import com.usuario.Medico.dto.*;
import com.usuario.Medico.service.AiService;
import com.usuario.Medico.service.CitaService;
import com.usuario.Medico.service.ReporteService;
import com.usuario.Medico.service.UsuarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final UsuarioService usuarioService;
    private final CitaService citaService;
    private final ReporteService reporteService;
    private final AiService aiService;

    @GetMapping("/usuarios")
    public ResponseEntity<List<UsuarioDTO>> listarUsuarios() {
        return ResponseEntity.ok(usuarioService.listarTodos());
    }

    @PostMapping("/usuarios")
    public ResponseEntity<UsuarioDTO> crearUsuario(@Valid @RequestBody UsuarioRequest request) {
        request.setRol("PACIENTE");
        return ResponseEntity.ok(usuarioService.crear(request));
    }

    @PutMapping("/usuarios/{id}")
    public ResponseEntity<UsuarioDTO> actualizarUsuario(
            @PathVariable Long id, @Valid @RequestBody UsuarioRequest request) {
        return ResponseEntity.ok(usuarioService.actualizar(id, request));
    }

    @DeleteMapping("/usuarios/{id}")
    public ResponseEntity<ApiMessage> eliminarUsuario(@PathVariable Long id) {
        usuarioService.eliminar(id);
        return ResponseEntity.ok(new ApiMessage("Usuario desactivado"));
    }

    @PostMapping("/medicos")
    public ResponseEntity<UsuarioDTO> crearMedico(@Valid @RequestBody UsuarioRequest request) {
        request.setRol("MEDICO");
        return ResponseEntity.ok(usuarioService.crear(request));
    }

    @GetMapping("/medicos")
    public ResponseEntity<List<UsuarioDTO>> listarMedicos() {
        return ResponseEntity.ok(usuarioService.listarMedicos());
    }

    @PutMapping("/medicos/{id}")
    public ResponseEntity<UsuarioDTO> actualizarMedico(
            @PathVariable Long id, @Valid @RequestBody UsuarioRequest request) {
        return ResponseEntity.ok(usuarioService.actualizarMedico(id, request));
    }

    @GetMapping("/citas")
    public ResponseEntity<List<CitaResponse>> supervisarCitas() {
        return ResponseEntity.ok(citaService.todasLasCitas());
    }

    @PatchMapping("/citas/{id}/reprogramar")
    public ResponseEntity<ReprogramarCitaResponse> reprogramarCita(
            @PathVariable Long id, @Valid @RequestBody ReprogramarCitaRequest request,
            org.springframework.security.core.Authentication authentication) {
        return ResponseEntity.ok(citaService.reprogramar(id, request, authentication.getName()));
    }

    @GetMapping("/reportes")
    public ResponseEntity<ReporteResponse> reportes(
            @RequestParam(defaultValue = "2026") int anio) {
        return ResponseEntity.ok(reporteService.generarReporte(anio));
    }

    @GetMapping("/ai-metricas")
    public ResponseEntity<AiMetricasResponse> aiMetricas() {
        return ResponseEntity.ok(reporteService.generarMetricasIA(aiService.hayApiKey()));
    }
}
