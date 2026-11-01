package com.minh.banking.customer.mapper;

import com.minh.banking.customer.entity.Customer;
import com.minh.banking.customer.dto.response.CustomerResponse;

public final class CustomerMapper {
    private CustomerMapper() {}

    public static CustomerResponse toResponse(Customer customer) {
        return new CustomerResponse(customer.getId(), customer.getCustomerCode(), customer.getFullName(),
            customer.getEmail(), customer.getPhone(), customer.getStatus(),
            customer.getCreatedAt(), customer.getUpdatedAt());
    }
}
