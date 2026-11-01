package com.minh.banking.customer.service;

import com.minh.banking.audit.AuditService;
import com.minh.banking.common.exception.*;
import com.minh.banking.customer.dto.request.UpdateCustomerRequest;
import com.minh.banking.customer.dto.response.CustomerResponse;
import com.minh.banking.customer.mapper.CustomerMapper;
import com.minh.banking.customer.repository.CustomerRepository;
import com.minh.banking.security.CurrentUser;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SelfProfileService {
    private final CustomerRepository customers;
    private final CurrentUser current;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    public SelfProfileService(CustomerRepository customers,CurrentUser current,AuditService audit,ApplicationEventPublisher events) {
        this.customers=customers;this.current=current;this.audit=audit;this.events=events;
    }
    @Transactional @PreAuthorize("hasRole('CUSTOMER')")
    public CustomerResponse update(UpdateCustomerRequest request) {
        if (request.email()==null && request.phone()==null) throw new BusinessException(ErrorCode.INVALID_REQUEST,"Provide a contact field.");
        var id=current.get().customerId();
        var customer=customers.findById(id).orElseThrow(()->new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND,"Customer was not found."));
        if (request.email()!=null) customer.changeEmail(normalize(request.email(),true));
        if (request.phone()!=null) customer.changePhone(normalize(request.phone(),false));
        var response=CustomerMapper.toResponse(customers.saveAndFlush(customer));
        audit.success(AuditService.Action.CUSTOMER_UPDATED,"CUSTOMER",id,Map.of("selfService",true));
        events.publishEvent(new CustomerProfileChanged(id));
        return response;
    }
    private String normalize(String value,boolean email) {
        if (value.isBlank()) return null;
        return email?value.strip().toLowerCase(Locale.ROOT):value.strip();
    }
}
