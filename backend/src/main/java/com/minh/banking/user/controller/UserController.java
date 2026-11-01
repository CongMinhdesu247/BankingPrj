package com.minh.banking.user.controller;

import com.minh.banking.common.response.PageResponse;
import com.minh.banking.user.dto.*;
import com.minh.banking.user.enums.RoleCode;
import com.minh.banking.user.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1") @PreAuthorize("hasRole('ADMIN')")
public class UserController {
    private final UserService users;
    public UserController(UserService users) { this.users = users; }
    @PostMapping("/users")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "User created")
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        var user = users.create(request);
        return ResponseEntity.created(URI.create("/api/v1/users/" + user.id())).body(user);
    }
    @GetMapping("/users")
    public PageResponse<UserResponse> list(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) { return users.list(page, size); }
    @GetMapping("/users/{id}") public UserResponse get(@PathVariable UUID id) { return users.get(id); }
    @PatchMapping("/users/{id}/roles") public UserResponse roles(@PathVariable UUID id, @Valid @RequestBody ChangeUserRolesRequest request) {
        return users.changeRoles(id, request);
    }
    @PatchMapping("/users/{id}/status") public UserResponse status(@PathVariable UUID id, @Valid @RequestBody ChangeUserStatusRequest request) {
        return users.changeStatus(id, request);
    }
    @GetMapping("/roles") public List<RoleCode> roles() { return List.of(RoleCode.values()); }
}
