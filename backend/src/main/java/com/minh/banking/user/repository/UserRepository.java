package com.minh.banking.user.repository;

import com.minh.banking.user.entity.User;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {
    @Query("select distinct u from User u left join fetch u.roles where u.username=:username")
    Optional<User> findWithRolesByUsername(@Param("username") String username);
    @Query("select distinct u from User u left join fetch u.roles where u.id=:id")
    Optional<User> findWithRolesById(@Param("id") UUID id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id=:id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);
}
