package com.minh.banking.fraud;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class RiskScoreService {
    private final Map<FraudRule.Code,FraudRuleEvaluator> evaluators;
    public RiskScoreService(List<FraudRuleEvaluator> evaluators) {
        this.evaluators=evaluators.stream().collect(Collectors.toUnmodifiableMap(FraudRuleEvaluator::code,Function.identity()));
    }
    public FraudResult evaluate(List<FraudRule> rules,Function<FraudRule,FraudContext> contexts) {
        var matches=new ArrayList<FraudResult.Match>();
        for (var rule:rules) if (rule.enabled()) evaluators.get(rule.code()).evaluate(rule,contexts.apply(rule)).ifPresent(matches::add);
        int score=Math.min(100,matches.stream().mapToInt(FraudResult.Match::scoreContribution).sum());
        return new FraudResult(score,RiskLevel.of(score),List.copyOf(matches));
    }
}
