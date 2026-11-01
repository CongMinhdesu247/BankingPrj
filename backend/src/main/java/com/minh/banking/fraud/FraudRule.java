package com.minh.banking.fraud;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

public record FraudRule(UUID id,Code code,int version,Threshold threshold,int weight,boolean enabled,
        UUID updatedBy,Instant updatedAt) {
    public enum Code { HIGH_AMOUNT, VELOCITY, NEW_BENEFICIARY, FAILED_LOGIN_CONTEXT }
    public record Threshold(
        @tools.jackson.databind.annotation.JsonDeserialize(using=com.minh.banking.transaction.dto.request.MoneyStringDeserializer.class)
        String amount,Integer count,Integer windowSeconds) {}
    public BigDecimal amount() { return new BigDecimal(threshold.amount()); }
    public static List<FraudRule> defaults() {
        return List.of(rule(Code.HIGH_AMOUNT,new Threshold("100000000",null,null),40),
            rule(Code.VELOCITY,new Threshold(null,5,600),30),
            rule(Code.NEW_BENEFICIARY,new Threshold("20000000",null,86400),30),
            rule(Code.FAILED_LOGIN_CONTEXT,new Threshold(null,5,900),20));
    }
    private static FraudRule rule(Code code,Threshold threshold,int weight) {
        return new FraudRule(UUID.nameUUIDFromBytes(("banking:fraud:"+code).getBytes(StandardCharsets.UTF_8)),
            code,1,threshold,weight,true,null,Instant.EPOCH);
    }
}
