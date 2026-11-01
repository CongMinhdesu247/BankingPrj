package com.minh.banking.fraud;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class HighAmountRule implements FraudRuleEvaluator {
    public FraudRule.Code code() { return FraudRule.Code.HIGH_AMOUNT; }
    public Optional<FraudResult.Match> evaluate(FraudRule rule,FraudContext context) {
        return context.amount().compareTo(rule.amount())>=0?Optional.of(FraudResult.match(rule,
            Map.of("amount",context.amount().toPlainString()),"AMOUNT_AT_OR_ABOVE_THRESHOLD")):Optional.empty();
    }
}
