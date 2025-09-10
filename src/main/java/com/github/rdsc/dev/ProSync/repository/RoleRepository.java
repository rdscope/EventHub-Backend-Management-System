package com.github.rdsc.dev.ProSync.repository;

import com.github.rdsc.dev.ProSync.enums.UserRole;
import com.github.rdsc.dev.ProSync.model.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {
    Optional<Role> findByUserRole(UserRole role);
    boolean existsByUserRole(String name);
}
