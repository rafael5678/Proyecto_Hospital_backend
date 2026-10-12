package com.usuario.Medico.repository;

import com.usuario.Medico.model.Cita;
import com.usuario.Medico.model.EstadoCita;
import com.usuario.Medico.model.Medico;
import com.usuario.Medico.model.Paciente;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CitaRepository extends JpaRepository<Cita, Long> {
    List<Cita> findByPacienteOrderByFechaHoraDesc(Paciente paciente);
    List<Cita> findByMedicoOrderByFechaHoraAsc(Medico medico);
    List<Cita> findAllByOrderByFechaHoraDesc();

    @Query("SELECT COUNT(c) FROM Cita c WHERE c.estado = :estado")
    long countByEstado(@Param("estado") EstadoCita estado);

    @Query("SELECT COUNT(c) FROM Cita c WHERE c.fechaHora BETWEEN :inicio AND :fin")
    long countByFechaHoraBetween(@Param("inicio") LocalDateTime inicio, @Param("fin") LocalDateTime fin);

    boolean existsByMedicoAndFechaHoraAndEstadoIn(Medico medico, LocalDateTime fechaHora, List<EstadoCita> estados);

    @Query("SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END FROM Cita c " +
            "WHERE c.medico = :medico AND c.estado IN :estados " +
            "AND c.fechaHora < :fin AND c.fechaHora > :inicioMenosDuracion " +
            "AND (:citaExcluida IS NULL OR c.id <> :citaExcluida)")
    boolean existeCitaSolapada(@Param("medico") Medico medico,
                               @Param("inicioMenosDuracion") LocalDateTime inicioMenosDuracion,
                               @Param("fin") LocalDateTime fin,
                               @Param("estados") List<EstadoCita> estados,
                               @Param("citaExcluida") Long citaExcluida);

    /* ====== Métricas IA para admin ====== */
    @Query("SELECT COUNT(c) FROM Cita c WHERE c.triageSeveridad IS NOT NULL")
    long countConTriage();

    @Query("SELECT COUNT(c) FROM Cita c WHERE c.dermatologiaScoreRiesgo IS NOT NULL")
    long countConEvaluacionPiel();

    @Query("SELECT COUNT(c) FROM Cita c WHERE c.triagePrioridad >= 8 " +
            "OR UPPER(c.triageSeveridad) IN ('ROJO','NARANJA')")
    long countPrioridadAlta();

    @Query("SELECT c.triageSeveridad, COUNT(c) FROM Cita c " +
            "WHERE c.triageSeveridad IS NOT NULL GROUP BY c.triageSeveridad")
    List<Object[]> distribucionSeveridad();

    /* ====== Repriorización: buscar cita solapada con menor prioridad ====== */
    @Query("SELECT c FROM Cita c WHERE c.medico = :medico AND c.estado IN :estados " +
            "AND c.fechaHora >= :inicio AND c.fechaHora < :fin " +
            "ORDER BY COALESCE(c.triagePrioridad, 5) ASC")
    List<Cita> findCitasSolapadasOrdenPrioridad(@Param("medico") Medico medico,
                                                 @Param("inicio") LocalDateTime inicio,
                                                 @Param("fin") LocalDateTime fin,
                                                 @Param("estados") List<EstadoCita> estados);
}
