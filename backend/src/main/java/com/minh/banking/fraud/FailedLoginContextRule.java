package com.minh.banking.fraud;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class FailedLoginContextRule implements FraudRuleEvaluator {
    public FraudRule.Code code() { return FraudRule.Code.FAILED_LOGIN_CONTEXT; }
    public Optional<FraudResult.Match> evaluate(FraudRule rule,FraudContext context) {
        return context.actorId()!=null && context.failedLoginCount()>=rule.threshold().count()?Optional.of(FraudResult.match(rule,
            Map.of("count",context.failedLoginCount(),"windowSeconds",rule.threshold().windowSeconds()),
            "ACTOR_FAILED_LOGINS_BEFORE_TRANSFER")):Optional.empty();
    }
}
