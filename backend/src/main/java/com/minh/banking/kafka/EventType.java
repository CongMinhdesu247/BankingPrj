package com.minh.banking.kafka;

public enum EventType {
    TRANSFER_COMPLETED("transfer.completed.v1"), TRANSFER_FAILED("transfer.failed.v1"), FRAUD_DETECTED("fraud.detected.v1");
    private final String suffix;
    EventType(String suffix) { this.suffix=suffix; }
    public String topic(String prefix) { return prefix+"."+suffix; }
}
