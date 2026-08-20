package com.skala.helpdesk.web.dto;

import com.skala.helpdesk.domain.InventoryItem;
import io.swagger.v3.oas.annotations.media.Schema;

public record InventoryItemResponse(
        @Schema(example = "달빛 대검") String itemName,
        @Schema(example = "전설") String grade,
        @Schema(example = "1") int quantity) {

    public static InventoryItemResponse from(InventoryItem item) {
        return new InventoryItemResponse(
                item.getItemName(), item.getGrade().label(), item.getQuantity());
    }
}
