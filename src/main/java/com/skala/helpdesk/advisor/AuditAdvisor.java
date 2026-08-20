package com.skala.helpdesk.advisor;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 감사 로깅 — 체인의 가장 바깥(order 0)에 선다.
 *
 * 가장 바깥이어야 하는 이유: 안전 필터에서 차단된 요청도 기록되어야 한다.
 * 차단이 감사보다 바깥에 있으면, 막힌 요청은 흔적조차 남지 않는다.
 *
 * 동기·스트리밍 두 인터페이스를 모두 구현한다.
 * CallAdvisor 만 구현한 Advisor 는 스트리밍 경로에서 조용히 건너뛰어진다.
 */
@Component
public class AuditAdvisor implements CallAdvisor, StreamAdvisor {

    public static final int ORDER = AdvisorOrder.AUDIT;
    private static final Logger log = LoggerFactory.getLogger(AuditAdvisor.class);

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String traceId = beginTrace();
        long started = System.nanoTime();
        try {
            ChatClientResponse response = chain.nextCall(request);
            log.info("[{}] chat 완료 · {}ms", traceId, elapsedMillis(started));
            return response;
        } catch (RuntimeException e) {
            log.warn("[{}] chat 실패 · {}ms · {}", traceId, elapsedMillis(started), e.toString());
            throw e;
        } finally {
            MDC.remove("traceId");
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        String traceId = beginTrace();
        long started = System.nanoTime();
        return chain.nextStream(request)
                .doOnComplete(() -> log.info("[{}] stream 완료 · {}ms", traceId, elapsedMillis(started)))
                .doOnError(e -> log.warn("[{}] stream 실패 · {}", traceId, e.toString()))
                .doOnCancel(() -> log.info("[{}] stream 취소 — 클라이언트 이탈", traceId))
                .doFinally(signal -> MDC.remove("traceId"));
    }

    /** 한 요청을 처음부터 끝까지 따라갈 수 있도록 추적 ID 를 심는다. */
    private String beginTrace() {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("traceId", traceId);
        return traceId;
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    @Override
    public String getName() {
        return "audit";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
