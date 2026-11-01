package com.minh.banking.account.service;

import java.security.SecureRandom;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class AccountNumberGenerator {
    private final SecureRandom random = new SecureRandom();

    public String next() {
        // Demo format only; this does not claim to be Agribank's numbering scheme.
        return "10" + String.format(Locale.ROOT, "%011d", random.nextLong(100_000_000_000L));
    }
}
