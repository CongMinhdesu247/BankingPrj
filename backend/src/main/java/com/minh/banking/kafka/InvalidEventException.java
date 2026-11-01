package com.minh.banking.kafka;
public class InvalidEventException extends RuntimeException {
    public InvalidEventException() { super("Event contract or PostgreSQL evidence is invalid"); }
}
