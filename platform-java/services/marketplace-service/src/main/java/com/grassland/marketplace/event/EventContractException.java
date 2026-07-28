package com.grassland.marketplace.event;

public class EventContractException extends RuntimeException {

    public EventContractException(String message) {
        super(message);
    }

    public EventContractException(String message, Throwable cause) {
        super(message, cause);
    }
}
