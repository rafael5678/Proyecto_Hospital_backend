package com.hospy.repository;

import com.hospy.model.Specialty;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Spring Data access for table especialidades. */
public interface SpecialtyRepository extends JpaRepository<Specialty, Long> {
    List<Specialty> findAllByOrderByNombreAsc();
}
