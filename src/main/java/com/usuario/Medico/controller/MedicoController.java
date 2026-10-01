package com.usuario.Medico.controller;

import com.usuario.Medico.dto.HorarioResponse;
import com.usuario.Medico.dto.UsuarioDTO;
import com.usuario.Medico.service.HorarioService;
import com.usuario.Medico.service.UsuarioService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/medicos")
@RequiredArgsConstructor
public class MedicoController {

    private final UsuarioService usuarioService;
    private final HorarioService horarioService;

    @GetMapping
    public ResponseEntity<List<UsuarioDTO>> listarMedicos() {
        return ResponseEntity.ok(usuarioService.listarMedicos());
    }

    @GetMapping("/{id}/horarios")
    public ResponseEntity<List<HorarioResponse>> horarios(@PathVariable Long id) {
        return ResponseEntity.ok(horarioService.listarPorMedico(id));
    }
}
