package com.skala.helpdesk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 복구·이의신청 티켓.
 *
 * 생성 시점의 상태는 언제나 PENDING 이다.
 * 도구가 만들 수 있는 최대치가 '접수'이고, 그 이상은 사람이 누른다.
 */
@Entity
@Table(name = "tickets")
public class Ticket {

    @Id
    private String no;              // 예: T-0007

    @Column(nullable = false)
    private String ownerId;

    private String characterId;     // 제재 이의신청은 캐릭터와 무관할 수 있다

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TicketType type;

    @Column(nullable = false, length = 1000)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TicketStatus status;

    private LocalDateTime createdAt;

    protected Ticket() {
    }

    public Ticket(String no, String ownerId, String characterId, TicketType type, String detail) {
        this.no = no;
        this.ownerId = ownerId;
        this.characterId = characterId;
        this.type = type;
        this.detail = detail;
        this.status = TicketStatus.PENDING;    // 접수는 언제나 PENDING 으로 시작한다
        this.createdAt = LocalDateTime.now();
    }

    public void approve() { this.status = TicketStatus.APPROVED; }
    public void reject()  { this.status = TicketStatus.REJECTED; }

    public String getNo()               { return no; }
    public String getOwnerId()          { return ownerId; }
    public String getCharacterId()      { return characterId; }
    public TicketType getType()         { return type; }
    public String getDetail()           { return detail; }
    public TicketStatus getStatus()     { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
