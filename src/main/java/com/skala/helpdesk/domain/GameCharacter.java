package com.skala.helpdesk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 게임 캐릭터. 조회의 기준 단위이자 권한 경계의 기준이다.
 *
 * ownerId(계정)는 내부 전용이다. 응답 DTO 에 담지 않는다.
 * 남의 캐릭터를 조회하려는 시도는 이 필드 하나로 막힌다.
 */
@Entity
@Table(name = "game_characters")
public class GameCharacter {

    @Id
    private String id;              // 예: CH-1001

    @Column(nullable = false)
    private String ownerId;         // 계정 ID — 내부 전용

    @Column(nullable = false)
    private String nickname;

    @Column(nullable = false)
    private String job;

    private int level;

    private String server;

    private LocalDateTime lastPlayedAt;

    protected GameCharacter() {
    }

    public GameCharacter(String id, String ownerId, String nickname, String job,
                         int level, String server, LocalDateTime lastPlayedAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.nickname = nickname;
        this.job = job;
        this.level = level;
        this.server = server;
        this.lastPlayedAt = lastPlayedAt;
    }

    public String getId()                  { return id; }
    public String getOwnerId()             { return ownerId; }
    public String getNickname()            { return nickname; }
    public String getJob()                 { return job; }
    public int getLevel()                  { return level; }
    public String getServer()              { return server; }
    public LocalDateTime getLastPlayedAt() { return lastPlayedAt; }
}
