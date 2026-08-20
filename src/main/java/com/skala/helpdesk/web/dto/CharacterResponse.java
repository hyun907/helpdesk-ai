package com.skala.helpdesk.web.dto;

import com.skala.helpdesk.domain.GameCharacter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 응답 DTO 는 내보낼 필드만 고른다.
 * ownerId(계정 식별자)는 여기서 버려진다.
 */
public record CharacterResponse(
        @Schema(example = "CH-1001") String characterId,
        @Schema(example = "달빛기사") String nickname,
        @Schema(example = "전사") String job,
        @Schema(example = "87") int level,
        @Schema(example = "아스가르드") String server,
        LocalDateTime lastPlayedAt) {

    public static CharacterResponse from(GameCharacter c) {
        return new CharacterResponse(
                c.getId(), c.getNickname(), c.getJob(),
                c.getLevel(), c.getServer(), c.getLastPlayedAt());
    }
}
