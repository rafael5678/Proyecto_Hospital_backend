package com.hospy.repository;

import com.hospy.model.AppointmentChange;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access for table cambios_cita. */
public interface AppointmentChangeRepository extends JpaRepository<AppointmentChange, Long> {
    long countByRealizadoPor(String realizadoPor);
}