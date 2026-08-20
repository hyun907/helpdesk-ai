package com.skala.helpdesk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 설정 외부화 — 코드에 상수를 남기지 않는다.
 * 검색 임계값이나 메모리 윈도우 크기를 바꾸려고 코드를 고치는 일이 없어야 한다.
 */
@ConfigurationProperties(prefix = "helpdesk")
public record HelpDeskProperties(Rag rag, Memory memory, Fallback fallback) {

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
}
