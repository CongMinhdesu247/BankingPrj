package com.minh.banking.fraud;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class VelocityRule implements FraudRuleEvaluator {
    public FraudRule.Code code() { return FraudRule.Code.VELOCITY; }
    public Optional<FraudResult.Match> evaluate(FraudRule rule,FraudContext context) {
        return context.velocityCount()>=rule.threshold().count()?Optional.of(FraudResult.match(rule,
            Map.of("count",context.velocityCount(),"windowSeconds",rule.threshold().windowSeconds(),"includesCurrentTransfer",true),
            "OUTGOING_TRANSFER_COUNT_AT_OR_ABOVE_THRESHOLD")):Optional.empty();
    }
}
