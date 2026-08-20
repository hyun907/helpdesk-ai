package com.skala.helpdesk.domain;

public enum ItemGrade {
    COMMON("일반"),
    RARE("희귀"),
    HERO("영웅"),
    LEGEND("전설");

    private final String label;

    ItemGrade(String label) { this.label = label; }

    public String label() { return label; }

    /** 전설 등급은 복구 시 GM 승인 외에 추가 검토가 붙는다. 시세가 경제에 영향을 준다. */
    public boolean requiresExtraReview() { return this == LEGEND; }
}
