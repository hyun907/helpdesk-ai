package com.skala.helpdesk.web.dto;

import com.skala.helpdesk.domain.Order;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/**
 * 응답 DTO 는 내보낼 필드만 고른다.
 * ownerId·cost 는 여기서 버려진다. 변환은 한 곳에서(정적 팩터리).
 */
public record OrderResponse(
        @Schema(example = "12345") String orderId,
        @Schema(example = "무선 이어폰") String item,
        @Schema(example = "배송중") String status,
        @Schema(example = "2026-08-23") LocalDate eta) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getItem(),
                order.getStatus().label(),
                order.getEta());
    }
}
