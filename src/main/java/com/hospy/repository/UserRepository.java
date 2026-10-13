package com.hospy.repository;

import com.hospy.model.Role;
import com.hospy.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    List<User> findByRol(Role rol);
    List<User> findByRolAndActivoTrue(Role rol);
}
