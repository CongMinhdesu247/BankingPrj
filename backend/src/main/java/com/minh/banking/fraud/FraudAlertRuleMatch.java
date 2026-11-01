package com.minh.banking.fraud;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record FraudAlertRuleMatch(UUID id,FraudRule.Code ruleCode,int ruleVersion,int scoreContribution,
        JsonNode observedValue,JsonNode thresholdSnapshot,String reason) {}
