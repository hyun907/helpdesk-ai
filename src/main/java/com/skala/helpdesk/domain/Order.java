package com.skala.helpdesk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 엔티티는 밖으로 나가지 않는다.
 * ownerId(내부 전용)와 cost(원가)는 응답 DTO 에 담지 않는다.
 */
@Entity
@Table(name = "orders")          // order 는 SQL 예약어라 테이블명을 따로 준다
public class Order {

    @Id
    private String id;

    @Column(nullable = false)
    private String ownerId;       // 내부 전용 — 응답에 없다

    @Column(nullable = false)
    private String item;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    private LocalDate eta;        // 예상 도착일

    private LocalDate orderedAt;

    private BigDecimal cost;      // 원가 — 절대 노출하면 안 된다

    protected Order() {
    }

    public Order(String id, String ownerId, String item, OrderStatus status,
                 LocalDate eta, LocalDate orderedAt, BigDecimal cost) {
        this.id = id;
        this.ownerId = ownerId;
        this.item = item;
        this.status = status;
        this.eta = eta;
        this.orderedAt = orderedAt;
        this.cost = cost;
    }

    public String getId()          { return id; }
    public String getOwnerId()     { return ownerId; }
    public String getItem()        { return item; }
    public OrderStatus getStatus() { return status; }
    public LocalDate getEta()      { return eta; }
    public LocalDate getOrderedAt() { return orderedAt; }
}
