package com.minh.banking.fraud;

import java.util.*;

public interface FraudRuleEvaluator {
    FraudRule.Code code();
    Optional<FraudResult.Match> evaluate(FraudRule rule,FraudContext context);
}
