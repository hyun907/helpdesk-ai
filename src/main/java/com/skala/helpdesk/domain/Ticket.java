package com.skala.helpdesk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "tickets")
public class Ticket {

    @Id
    private String no;             // 예: T-0007

    @Column(nullable = false)
    private String orderId;

    @Column(nullable = false)
    private String ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TicketType type;

    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TicketStatus status;

    private LocalDateTime createdAt;

    protected Ticket() {
    }

    public Ticket(String no, String orderId, String ownerId, TicketType type, String reason) {
        this.no = no;
        this.orderId = orderId;
        this.ownerId = ownerId;
        this.type = type;
        this.reason = reason;
        this.status = TicketStatus.PENDING;    // 접수는 언제나 PENDING 으로 시작한다
        this.createdAt = LocalDateTime.now();
    }

    public void approve() { this.status = TicketStatus.APPROVED; }
    public void reject()  { this.status = TicketStatus.REJECTED; }

    public String getNo()            { return no; }
    public String getOrderId()       { return orderId; }
    public String getOwnerId()       { return ownerId; }
    public TicketType getType()      { return type; }
    public String getReason()        { return reason; }
    public TicketStatus getStatus()  { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
