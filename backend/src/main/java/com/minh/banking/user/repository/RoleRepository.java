package com.minh.banking.user.repository;

import com.minh.banking.user.entity.Role;
import com.minh.banking.user.enums.RoleCode;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, UUID> {
    List<Role> findAllByCodeIn(Set<RoleCode> codes);
}
