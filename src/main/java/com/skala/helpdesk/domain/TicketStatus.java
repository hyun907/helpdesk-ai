package com.skala.helpdesk.domain;

/**
 * 도구는 접수(PENDING)까지만 만든다.
 * APPROVED 로 넘기는 일은 GM 이 누른다 — 모델이 닿을 수 없는 경로다.
 */
public enum TicketStatus {
    PENDING("승인대기"),
    APPROVED("승인됨"),
    REJECTED("반려됨");

    private final String label;

    TicketStatus(String label) { this.label = label; }

    public String label() { return label; }
}
