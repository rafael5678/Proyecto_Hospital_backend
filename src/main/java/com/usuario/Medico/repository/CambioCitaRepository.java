package com.usuario.Medico.repository;

import com.usuario.Medico.model.CambioCita;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CambioCitaRepository extends JpaRepository<CambioCita, Long> {
    long countByRealizadoPor(String realizadoPor);
}