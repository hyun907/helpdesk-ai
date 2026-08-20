package com.skala.helpdesk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 제재 이력. 계정 단위로 남는다.
 * 이의신청의 대상이며, 해제는 GM 승인을 거친다.
 */
@Entity
@Table(name = "sanctions")
public class Sanction {

    @Id
    private String id;              // 예: SC-2001

    @Column(nullable = false)
    private String ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SanctionType type;

    @Column(nullable = false)
    private String reason;

    private LocalDateTime startedAt;

    private LocalDateTime endsAt;   // 영구 정지는 null

    protected Sanction() {
    }

    public Sanction(String id, String ownerId, SanctionType type, String reason,
                    LocalDateTime startedAt, LocalDateTime endsAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.type = type;
        this.reason = reason;
        this.startedAt = startedAt;
        this.endsAt = endsAt;
    }

    public boolean isActive() {
        return endsAt == null || endsAt.isAfter(LocalDateTime.now());
    }

    public String getId()               { return id; }
    public String getOwnerId()          { return ownerId; }
    public SanctionType getType()       { return type; }
    public String getReason()           { return reason; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getEndsAt()    { return endsAt; }
}
