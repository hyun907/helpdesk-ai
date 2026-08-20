package com.skala.helpdesk.web.dto;

/**
 * 사용자에겐 안전한 문구와 추적 ID만, 상세는 로그에만 남긴다.
 * 스택트레이스를 응답에 담으면 내부 구조가 그대로 노출된다.
 */
public record ErrorResponse(String message, String traceId) {
}
