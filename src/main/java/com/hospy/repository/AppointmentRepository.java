package com.hospy.repository;

import com.hospy.model.Appointment;
import com.hospy.model.AppointmentStatus;
import com.hospy.model.Doctor;
import com.hospy.model.Patient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {
    List<Appointment> findByPacienteOrderByFechaHoraDesc(Patient paciente);
    List<Appointment> findByMedicoOrderByFechaHoraAsc(Doctor medico);
    List<Appointment> findAllByOrderByFechaHoraDesc();
    List<Appointment> findByEstado(AppointmentStatus estado);

    @Query("SELECT COUNT(c) FROM Appointment c WHERE c.estado = :estado")
    long countByEstado(@Param("estado") AppointmentStatus estado);

    @Query("SELECT COUNT(c) FROM Appointment c WHERE c.fechaHora BETWEEN :inicio AND :fin")
    long countByFechaHoraBetween(@Param("inicio") LocalDateTime inicio, @Param("fin") LocalDateTime fin);

    boolean existsByMedicoAndFechaHoraAndEstadoIn(Doctor medico, LocalDateTime fechaHora, List<AppointmentStatus> estados);

    @Query("SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END FROM Appointment c " +
            "WHERE c.medico = :medico AND c.estado IN :estados " +
            "AND c.fechaHora < :fin AND c.fechaHora > :inicioMenosDuracion " +
            "AND (:citaExcluida IS NULL OR c.id <> :citaExcluida)")
    boolean existeCitaSolapada(@Param("medico") Doctor medico,
                               @Param("inicioMenosDuracion") LocalDateTime inicioMenosDuracion,
                               @Param("fin") LocalDateTime fin,
                               @Param("estados") List<AppointmentStatus> estados,
                               @Param("citaExcluida") Long citaExcluida);

    /* ====== Métricas IA para admin ====== */
    @Query("SELECT COUNT(c) FROM Appointment c WHERE c.triageSeveridad IS NOT NULL")
    long countConTriage();

    @Query("SELECT COUNT(c) FROM Appointment c WHERE c.dermatologiaScoreRiesgo IS NOT NULL")
    long countConEvaluacionPiel();

    @Query("SELECT COUNT(c) FROM Appointment c WHERE c.triagePrioridad >= 8 " +
            "OR UPPER(c.triageSeveridad) IN ('ROJO','NARANJA')")
    long countPrioridadAlta();

    @Query("SELECT c.triageSeveridad, COUNT(c) FROM Appointment c " +
            "WHERE c.triageSeveridad IS NOT NULL GROUP BY c.triageSeveridad")
    List<Object[]> distribucionSeveridad();

    /* ====== Repriorización: buscar cita solapada con menor prioridad ====== */
    @Query("SELECT c FROM Appointment c WHERE c.medico = :medico AND c.estado IN :estados " +
            "AND c.fechaHora >= :inicio AND c.fechaHora < :fin " +
            "ORDER BY COALESCE(c.triagePrioridad, 5) ASC")
    List<Appointment> findCitasSolapadasOrdenPrioridad(@Param("medico") Doctor medico,
                                                 @Param("inicio") LocalDateTime inicio,
                                                 @Param("fin") LocalDateTime fin,
                                                 @Param("estados") List<AppointmentStatus> estados);
}
