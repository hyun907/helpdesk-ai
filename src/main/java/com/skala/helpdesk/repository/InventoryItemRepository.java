package com.skala.helpdesk.repository;

import com.skala.helpdesk.domain.InventoryItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, String> {

    List<InventoryItem> findByCharacterIdOrderByGradeDesc(String characterId);
}
