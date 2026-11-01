package com.minh.banking.fraud;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class NewBeneficiaryRule implements FraudRuleEvaluator {
    public FraudRule.Code code() { return FraudRule.Code.NEW_BENEFICIARY; }
    public Optional<FraudResult.Match> evaluate(FraudRule rule,FraudContext context) {
        boolean unsaved=context.beneficiaryCreatedAt()==null;
        boolean recent=unsaved || (context.beneficiaryCreatedAt().isAfter(context.occurredAt().minusSeconds(rule.threshold().windowSeconds()))
            && !context.beneficiaryCreatedAt().isAfter(context.occurredAt()));
        return recent && context.amount().compareTo(rule.amount())>=0?Optional.of(FraudResult.match(rule,
            unsaved?Map.of("amount",context.amount().toPlainString(),"beneficiaryState","UNSAVED_AT_TRANSFER"):
                Map.of("amount",context.amount().toPlainString(),"beneficiaryState","NEW_AT_TRANSFER","createdAt",context.beneficiaryCreatedAt().toString()),
            unsaved?"UNSAVED_RECIPIENT_AND_HIGH_AMOUNT":"RECENT_BENEFICIARY_AND_HIGH_AMOUNT")):Optional.empty();
    }
}
