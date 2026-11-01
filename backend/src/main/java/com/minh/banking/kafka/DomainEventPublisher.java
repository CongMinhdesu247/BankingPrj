package com.minh.banking.kafka;
public interface DomainEventPublisher { void publish(EventEnvelope event,String sourceAccountKey); }
