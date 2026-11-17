package com.orderflow.engine;

public class EngineBusyException extends RuntimeException {
    public EngineBusyException(String message) {
        super(message);
    }
}
