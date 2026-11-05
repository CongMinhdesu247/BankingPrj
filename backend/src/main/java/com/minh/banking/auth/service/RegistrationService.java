package com.minh.banking.auth.service;

import com.minh.banking.auth.dto.RegisterRequest;
import com.minh.banking.account.entity.Account;
import com.minh.banking.account.repository.AccountRepository;
import com.minh.banking.account.service.AccountNumberGenerator;
import com.minh.banking.audit.AuditService;
import com.minh.banking.common.exception.*;
import com.minh.banking.customer.entity.Customer;
import com.minh.banking.customer.repository.CustomerRepository;
import com.minh.banking.user.entity.User;
import com.minh.banking.user.enums.RoleCode;
import com.minh.banking.user.repository.*;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationService {
    private final CustomerRepository customers;
    private final UserRepository users;
    private final RoleRepository roles;
    private final AccountRepository accounts;
    private final AccountNumberGenerator numbers;
    private final PasswordEncoder passwords;
    private final AuditService audit;
    public RegistrationService(CustomerRepository customers,UserRepository users,RoleRepository roles,
            AccountRepository accounts,AccountNumberGenerator numbers,PasswordEncoder passwords,AuditService audit) {
        this.customers=customers;this.users=users;this.roles=roles;this.accounts=accounts;
        this.numbers=numbers;this.passwords=passwords;this.audit=audit;
    }
    @Transactional
    public Result register(RegisterRequest request) {
        var customerRoles=roles.findAllByCodeIn(Set.of(RoleCode.CUSTOMER));
        if(customerRoles.size()!=1) throw new BusinessException(ErrorCode.INTERNAL_ERROR,"Registration is unavailable.");
        // All identifiers, role, status and balance are server-owned. Never attach an existing customer by email.
        try {
            var customer=customers.saveAndFlush(new Customer("CUS"+UUID.randomUUID().toString().replace("-","").substring(0,27),
                request.fullName(),request.email(),request.phone()));
            var user=users.saveAndFlush(new User(request.username(),passwords.encode(request.password()),customer.getId(),Set.copyOf(customerRoles)));
            var account=accounts.saveAndFlush(new Account(customer,numbers.next()));
            var details=Map.of("channel","SELF_REGISTRATION");
            audit.write(null,AuditService.Action.CUSTOMER_CREATED,"CUSTOMER",customer.getId(),true,details);
            audit.write(null,AuditService.Action.USER_CREATED,"USER",user.getId(),true,details);
            audit.write(null,AuditService.Action.ACCOUNT_OPENED,"ACCOUNT",account.getId(),true,details);
            return new Result(user.getUsername(),account.getAccountNumber());
        } catch(DataIntegrityViolationException failure) {
            // Database uniqueness constraints arbitrate concurrent registrations; the complete transaction rolls back.
            throw new BusinessException(ErrorCode.REGISTRATION_CONFLICT,"Registration details are already in use. Choose another username or email.");
        }
    }
    public record Result(String username,String accountNumber) {}
}
