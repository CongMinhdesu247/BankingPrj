package com.minh.banking.fraud;

public enum RiskLevel {
    LOW, MEDIUM, HIGH, CRITICAL;
    public static RiskLevel of(int score) {
        if (score<0 || score>100) throw new IllegalArgumentException("Score must be between 0 and 100");
        return score>=80?CRITICAL:score>=60?HIGH:score>=30?MEDIUM:LOW;
    }
}
