package com.skala.helpdesk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** 캐릭터가 보유한 아이템. 복구 신청의 대상이 된다. */
@Entity
@Table(name = "inventory_items")
public class InventoryItem {

    @Id
    private String id;

    @Column(nullable = false)
    private String characterId;

    @Column(nullable = false)
    private String itemName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ItemGrade grade;

    private int quantity;

    private LocalDateTime acquiredAt;

    protected InventoryItem() {
    }

    public InventoryItem(String id, String characterId, String itemName,
                         ItemGrade grade, int quantity, LocalDateTime acquiredAt) {
        this.id = id;
        this.characterId = characterId;
        this.itemName = itemName;
        this.grade = grade;
        this.quantity = quantity;
        this.acquiredAt = acquiredAt;
    }

    public String getId()                { return id; }
    public String getCharacterId()       { return characterId; }
    public String getItemName()          { return itemName; }
    public ItemGrade getGrade()          { return grade; }
    public int getQuantity()             { return quantity; }
    public LocalDateTime getAcquiredAt() { return acquiredAt; }
}
