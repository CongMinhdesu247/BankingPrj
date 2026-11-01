package com.minh.banking.limit;

import com.minh.banking.transaction.dto.request.MoneyStringDeserializer;
import jakarta.validation.constraints.NotBlank;
import tools.jackson.databind.annotation.JsonDeserialize;

public record UpdateLimitRequest(
    @NotBlank @JsonDeserialize(using=MoneyStringDeserializer.class) String perTransferAmount,
    @NotBlank @JsonDeserialize(using=MoneyStringDeserializer.class) String dailyAmount) {}
