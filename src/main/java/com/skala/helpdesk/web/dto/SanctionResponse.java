package com.skala.helpdesk.web.dto;

import com.skala.helpdesk.domain.Sanction;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

public record SanctionResponse(
        @Schema(example = "SC-2001") String sanctionId,
        @Schema(example = "채팅 제한") String type,
        @Schema(example = "부적절한 언어 사용") String reason,
        LocalDateTime startedAt,
        LocalDateTime endsAt,
        boolean active) {

    public static SanctionResponse from(Sanction s) {
        return new SanctionResponse(
                s.getId(), s.getType().label(), s.getReason(),
                s.getStartedAt(), s.getEndsAt(), s.isActive());
    }
}
