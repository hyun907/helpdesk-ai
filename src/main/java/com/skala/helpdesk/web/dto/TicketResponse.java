package com.skala.helpdesk.web.dto;

import com.skala.helpdesk.domain.Ticket;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 응답 DTO 는 내보낼 필드만 고른다.
 * ownerId(계정 식별자)는 여기서 버려진다 — GM 화면에 티켓 목록이 뜨는 순간
 * 계정 식별자가 화면·캐시·클라이언트 로그로 함께 퍼진다.
 *
 * enum 은 이름이 아니라 label 로 내보낸다.
 * PENDING 같은 내부 표현이 응답에 박히면 나중에 이름을 바꿀 자유가 사라진다.
 */
public record TicketResponse(
        @Schema(description = "티켓번호", example = "T-0007") String no,
        @Schema(description = "신청 종류", example = "아이템 복구") String type,
        @Schema(description = "처리 상태", example = "승인대기") String status,
        @Schema(description = "신청 사유", example = "점검 중 우편함에서 사라졌습니다") String detail,
        @Schema(description = "접수 시각") LocalDateTime createdAt) {

    public static TicketResponse from(Ticket ticket) {
        return new TicketResponse(
                ticket.getNo(),
                ticket.getType().label(),
                ticket.getStatus().label(),
                ticket.getDetail(),
                ticket.getCreatedAt());
    }
}
