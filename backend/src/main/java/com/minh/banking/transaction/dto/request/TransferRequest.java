package com.minh.banking.transaction.dto.request;

import jakarta.validation.constraints.*;
import java.util.UUID;
import tools.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;

public record TransferRequest(
    @NotNull UUID sourceAccountId,
    @NotNull UUID destinationAccountId,
    @JsonDeserialize(using = MoneyStringDeserializer.class)
    @Schema(type = "string", example = "200000", description = "Positive whole VND, at most 17 integer digits and 2 zero fractional digits")
    @NotBlank @Pattern(regexp = "[0-9]{1,17}(?:\\.0{1,2})?") String amount,
    @NotBlank @Pattern(regexp = "VND") String currency,
    @Size(max = 255) String description
) {}
