package com.minh.banking.fraud;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class RiskRulesTest {
    private static final Instant AT=Instant.parse("2026-10-27T12:00:00Z");
    private final RiskScoreService scores=new RiskScoreService(List.of(new HighAmountRule(),new VelocityRule(),new NewBeneficiaryRule(),new FailedLoginContextRule()));
    private FraudRule rule(FraudRule.Code code) { return FraudRule.defaults().stream().filter(r->r.code()==code).findFirst().orElseThrow(); }
    private FraudContext context(String amount,long velocity,Instant beneficiary,long failures) {
        return new FraudContext(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),new BigDecimal(amount),AT,velocity,beneficiary,failures);
    }
    @ParameterizedTest @CsvSource({"99999999,false","100000000,true","100000001,true"})
    void highAmountUsesInclusiveWholeVndThreshold(String amount,boolean matches) {
        var result=new HighAmountRule().evaluate(rule(FraudRule.Code.HIGH_AMOUNT),context(amount,0,null,0));
        assertThat(result.isPresent()).isEqualTo(matches);
    }
    @ParameterizedTest @CsvSource({"4,false","5,true","6,true"})
    void velocityThresholdIncludesCurrentTransfer(long count,boolean matches) {
        assertThat(new VelocityRule().evaluate(rule(FraudRule.Code.VELOCITY),context("1",count,null,0)).isPresent()).isEqualTo(matches);
    }
    @ParameterizedTest @CsvSource({"19999999,false","20000000,true","20000001,true"})
    void unsavedRecipientUsesInclusiveAmountThreshold(String amount,boolean matches) {
        var result=new NewBeneficiaryRule().evaluate(rule(FraudRule.Code.NEW_BENEFICIARY),context(amount,0,null,0));
        assertThat(result.isPresent()).isEqualTo(matches);
        result.ifPresent(match->assertThat(match.reason()).isEqualTo("UNSAVED_RECIPIENT_AND_HIGH_AMOUNT"));
    }
    @ParameterizedTest @CsvSource({"86399,true","86400,false","86401,false","0,true","-1,false"})
    void beneficiaryAgeIsStrictlyLessThanOneDayAndNeverInFuture(long age,boolean matches) {
        assertThat(new NewBeneficiaryRule().evaluate(rule(FraudRule.Code.NEW_BENEFICIARY),context("20000000",0,AT.minusSeconds(age),0)).isPresent()).isEqualTo(matches);
    }
    @ParameterizedTest @CsvSource({"4,false","5,true","6,true"})
    void failedLoginThresholdIsInclusive(long failures,boolean matches) {
        assertThat(new FailedLoginContextRule().evaluate(rule(FraudRule.Code.FAILED_LOGIN_CONTEXT),context("1",0,null,failures)).isPresent()).isEqualTo(matches);
    }
    @ParameterizedTest @CsvSource({"0,LOW","29,LOW","30,MEDIUM","59,MEDIUM","60,HIGH","79,HIGH","80,CRITICAL","100,CRITICAL"})
    void riskBoundariesAreExplicit(int score,RiskLevel level) { assertThat(RiskLevel.of(score)).isEqualTo(level); }
    @Test void aggregateCapsAt100WhileRetainingAllContributions() {
        var result=scores.evaluate(FraudRule.defaults(),r->context("100000000",5,null,5));
        assertThat(result.score()).isEqualTo(100); assertThat(result.level()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(result.matches()).hasSize(4);
        assertThat(result.matches().stream().mapToInt(FraudResult.Match::scoreContribution).sum()).isEqualTo(120);
    }
    @Test void disabledRulesDoNotLoadContextOrCreateMatches() {
        var disabled=FraudRule.defaults().stream().map(r->new FraudRule(r.id(),r.code(),r.version(),r.threshold(),r.weight(),false,null,Instant.EPOCH)).toList();
        var result=scores.evaluate(disabled,r->{ throw new AssertionError("Disabled context must not be queried"); });
        assertThat(result.score()).isZero(); assertThat(result.matches()).isEmpty();
    }
    @Test void zeroWeightMatchStillCreatesLowRiskEvidence() {
        var original=rule(FraudRule.Code.HIGH_AMOUNT);
        var zero=new FraudRule(original.id(),original.code(),1,original.threshold(),0,true,null,Instant.EPOCH);
        var result=scores.evaluate(List.of(zero),r->context("100000000",0,null,0));
        assertThat(result.score()).isZero(); assertThat(result.matches()).hasSize(1);
    }
    @Test void thresholdRejectsWrongFieldsAndFractionalOrOversizedAmounts() {
        for (String amount:List.of("0","-1","1.5","100000000000000000")) assertThatThrownBy(()->
            FraudRuleService.validate(FraudRule.Code.HIGH_AMOUNT,new FraudRule.Threshold(amount,null,null))).hasMessageContaining("invalid");
        assertThatThrownBy(()->FraudRuleService.validate(FraudRule.Code.VELOCITY,new FraudRule.Threshold("1",5,600))).hasMessageContaining("invalid");
        assertThatThrownBy(()->FraudRuleService.validate(FraudRule.Code.VELOCITY,new FraudRule.Threshold(null,5,0))).hasMessageContaining("invalid");
    }
}
