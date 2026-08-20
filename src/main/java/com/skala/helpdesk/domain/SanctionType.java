package com.skala.helpdesk.domain;

public enum SanctionType {
    CHAT_BLOCK("채팅 제한"),
    SUSPENSION("이용 정지"),
    PERMANENT_BAN("영구 정지");

    private final String label;

    SanctionType(String label) { this.label = label; }

    public String label() { return label; }
}
