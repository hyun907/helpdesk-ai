package com.skala.helpdesk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 설정 외부화 — 코드에 상수를 남기지 않는다.
 * 검색 임계값이나 메모리 윈도우 크기를 바꾸려고 코드를 고치는 일이 없어야 한다.
 */
@ConfigurationProperties(prefix = "helpdesk")
public record HelpDeskProperties(Rag rag, Memory memory, Fallback fallback, Recovery recovery) {

    public record Rag(
            int topK,           // 검색해서 가져올 청크 개수
            double threshold    // 이 점수 미만은 근거로 취급하지 않는다
    ) {}

    public record Memory(
            int maxMessages     // 대화 이력 윈도우 — 길어지면 토큰이 는다
    ) {}

    public record Fallback(
            String model        // 보조 모델 — 주 모델과 '다른' 모델이라는 점이 핵심이다
    ) {}

    /**
     * 복구 신청 기한. 정책 문서(item-recovery-policy.md)에 적힌 값을 코드가 따라간다.
     * 두 곳에 같은 숫자가 있는 것은 드리프트 위험이지만, 기한이 지난 신청을 접수해
     * 담당자 검토 큐를 채우는 것보다는 낫다. 문서를 고치면 이 값도 함께 고쳐야 한다.
     */
    public record Recovery(
            int claimWindowDays
    ) {}
}
