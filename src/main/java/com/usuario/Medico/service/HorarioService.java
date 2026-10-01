package com.usuario.Medico.service;

import com.usuario.Medico.dto.HorarioRequest;
import com.usuario.Medico.dto.HorarioResponse;
import com.usuario.Medico.model.Horario;
import com.usuario.Medico.model.Medico;
import com.usuario.Medico.repository.HorarioRepository;
import com.usuario.Medico.repository.MedicoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class HorarioService {

    private final HorarioRepository horarioRepository;
    private final MedicoRepository medicoRepository;

    public List<HorarioResponse> listarPorMedico(Long medicoId) {
        Medico medico = medicoRepository.findById(medicoId)
                .orElseThrow(() -> new RuntimeException("Médico no encontrado"));
        return horarioRepository.findByMedicoAndDisponibleTrue(medico)
                .stream().map(this::toDto).toList();
    }

    public List<HorarioResponse> misHorarios(String email) {
        Medico medico = medicoRepository.findByUsuario_Email(email)
                .orElseThrow(() -> new RuntimeException("Médico no encontrado"));
        return horarioRepository.findByMedico(medico).stream().map(this::toDto).toList();
    }

    public HorarioResponse crear(String emailMedico, HorarioRequest req) {
        Medico medico = medicoRepository.findByUsuario_Email(emailMedico)
                .orElseThrow(() -> new RuntimeException("Médico no encontrado"));
        Horario horario = Horario.builder()
                .medico(medico)
                .diaSemana(req.getDiaSemana())
                .horaInicio(req.getHoraInicio())
                .horaFin(req.getHoraFin())
                .disponible(req.getDisponible() != null ? req.getDisponible() : true)
                .build();
        return toDto(horarioRepository.save(horario));
    }

    public HorarioResponse actualizar(Long id, String emailMedico, HorarioRequest req) {
        Horario horario = horarioRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Horario no encontrado"));
        if (!horario.getMedico().getUsuario().getEmail().equals(emailMedico)) {
            throw new RuntimeException("No autorizado");
        }
        horario.setDiaSemana(req.getDiaSemana());
        horario.setHoraInicio(req.getHoraInicio());
        horario.setHoraFin(req.getHoraFin());
        if (req.getDisponible() != null) horario.setDisponible(req.getDisponible());
        return toDto(horarioRepository.save(horario));
    }

    public void eliminar(Long id, String emailMedico) {
        Horario horario = horarioRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Horario no encontrado"));
        if (!horario.getMedico().getUsuario().getEmail().equals(emailMedico)) {
            throw new RuntimeException("No autorizado");
        }
        horarioRepository.delete(horario);
    }

    private HorarioResponse toDto(Horario h) {
        return HorarioResponse.builder()
                .id(h.getId())
                .medicoId(h.getMedico().getId())
                .diaSemana(h.getDiaSemana())
                .horaInicio(h.getHoraInicio())
                .horaFin(h.getHoraFin())
                .disponible(h.getDisponible())
                .build();
    }
}
