package com.minh.banking.user.service;

import com.minh.banking.common.exception.*;
import com.minh.banking.common.response.PageResponse;
import com.minh.banking.customer.service.CustomerService;
import com.minh.banking.security.BankingPrincipal;
import com.minh.banking.user.dto.*;
import com.minh.banking.user.entity.*;
import com.minh.banking.user.enums.RoleCode;
import com.minh.banking.user.repository.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.data.domain.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional(readOnly = true)
public class UserService {
    private final UserRepository users;
    private final RoleRepository roles;
    private final CustomerService customers;
    private final PasswordEncoder passwords;
    private final com.minh.banking.audit.AuditService audit;
    public UserService(UserRepository users, RoleRepository roles, CustomerService customers, PasswordEncoder passwords,
            com.minh.banking.audit.AuditService audit) {
        this.users = users; this.roles = roles; this.customers = customers; this.passwords = passwords;
        this.audit=audit;
    }

    public Optional<BankingPrincipal> currentIdentity(UUID id) {
        return users.findWithRolesById(id).filter(User::isEnabled).filter(user -> !user.getRoles().isEmpty())
            .map(user -> new BankingPrincipal(user.getId(), user.getUsername(), user.getCustomerId(), roleCodes(user)));
    }

    public UserResponse get(UUID id) { return toResponse(require(id)); }

    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<UserResponse> list(int page, int size) {
        return PageResponse.from(users.findAll(PageRequest.of(page, size, Sort.by("createdAt", "id"))).map(UserService::toResponse));
    }

    @Transactional @PreAuthorize("hasRole('ADMIN')")
    public UserResponse create(CreateUserRequest request) {
        validateLink(request.roles(), request.customerId());
        if (request.customerId() != null) customers.get(request.customerId());
        User user = new User(request.username(), passwords.encode(request.password()), request.customerId(), resolveRoles(request.roles()));
        var response=toResponse(users.saveAndFlush(user));
        audit.success(com.minh.banking.audit.AuditService.Action.USER_CREATED,"USER",response.id(),Map.of("roles",request.roles()));
        return response;
    }

    @Transactional @PreAuthorize("hasRole('ADMIN')")
    public UserResponse changeRoles(UUID id, ChangeUserRolesRequest request) {
        User user = lock(id);
        validateLink(request.roles(), user.getCustomerId());
        user.changeRoles(resolveRoles(request.roles()));
        var response=toResponse(users.saveAndFlush(user));
        audit.success(com.minh.banking.audit.AuditService.Action.USER_ROLES_CHANGED,"USER",id,Map.of("roles",request.roles()));
        return response;
    }

    @Transactional @PreAuthorize("hasRole('ADMIN')")
    public UserResponse changeStatus(UUID id, ChangeUserStatusRequest request) {
        User user = lock(id);
        user.changeEnabled(request.enabled());
        var response=toResponse(users.saveAndFlush(user));
        audit.success(com.minh.banking.audit.AuditService.Action.USER_STATUS_CHANGED,"USER",id,Map.of("enabled",request.enabled()));
        return response;
    }

    private void validateLink(Set<RoleCode> codes, UUID customerId) {
        if (codes == null || codes.isEmpty() || codes.stream().anyMatch(Objects::isNull)
                || (codes.contains(RoleCode.CUSTOMER) && codes.size() != 1)) {
            throw new BusinessException(ErrorCode.INVALID_USER_ROLES, "CUSTOMER must be the only role; internal roles may be combined.");
        }
        if (codes.contains(RoleCode.CUSTOMER) != (customerId != null)) {
            throw new BusinessException(ErrorCode.CUSTOMER_LINK_REQUIRED, "CUSTOMER requires a customer link; internal users must not have one.");
        }
    }

    private Set<Role> resolveRoles(Set<RoleCode> codes) {
        Set<Role> found = new HashSet<>(roles.findAllByCodeIn(codes));
        if (found.size() != codes.size()) throw new BusinessException(ErrorCode.INVALID_USER_ROLES, "Role configuration was not found.");
        return found;
    }
    private User require(UUID id) { return users.findWithRolesById(id).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "User was not found.")); }
    private User lock(UUID id) { return users.findByIdForUpdate(id).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "User was not found.")); }
    private static Set<RoleCode> roleCodes(User user) { return user.getRoles().stream().map(Role::getCode).collect(Collectors.toSet()); }
    public static UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.isEnabled(), user.getCustomerId(), roleCodes(user), user.getCreatedAt(), user.getUpdatedAt());
    }
}
