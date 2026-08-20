package com.skala.helpdesk.domain;

public enum TicketType {
    EXCHANGE("교환"),
    REFUND("환불");

    private final String label;

    TicketType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
