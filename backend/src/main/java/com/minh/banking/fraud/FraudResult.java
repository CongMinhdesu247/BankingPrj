package com.minh.banking.fraud;

import java.util.*;

public record FraudResult(int score,RiskLevel level,List<Match> matches) {
    public record Match(FraudRule.Code ruleCode,int ruleVersion,int scoreContribution,
            Map<String,Object> observedValue,FraudRule.Threshold thresholdSnapshot,String reason) {}
    public static Match match(FraudRule rule,Map<String,Object> observed,String reason) {
        return new Match(rule.code(),rule.version(),rule.weight(),Map.copyOf(observed),rule.threshold(),reason);
    }
}
