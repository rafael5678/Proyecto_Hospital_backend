package com.usuario.Medico.repository;

import com.usuario.Medico.model.Horario;
import com.usuario.Medico.model.Medico;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HorarioRepository extends JpaRepository<Horario, Long> {
    List<Horario> findByMedicoAndDisponibleTrue(Medico medico);
    List<Horario> findByMedico(Medico medico);
}
