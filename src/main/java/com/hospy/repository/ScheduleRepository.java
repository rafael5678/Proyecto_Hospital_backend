package com.hospy.repository;

import com.hospy.model.Schedule;
import com.hospy.model.Doctor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScheduleRepository extends JpaRepository<Schedule, Long> {
    List<Schedule> findByMedicoAndDisponibleTrue(Doctor medico);
    List<Schedule> findByMedico(Doctor medico);
}
