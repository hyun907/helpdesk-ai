package com.skala.helpdesk.domain;

/**
 * 접수 가능한 신청 종류.
 * 둘 다 되돌리기 어려운 행동이라 GM 승인을 거친다.
 *   - 아이템 복구는 게임 내 재화를 생성한다
 *   - 제재 해제는 제재 이력의 효력을 없앤다
 */
public enum TicketType {
    ITEM_RECOVERY("아이템 복구"),
    SANCTION_APPEAL("제재 이의신청");

    private final String label;

    TicketType(String label) { this.label = label; }

    public String label() { return label; }
}
