package com.skala.helpdesk.advisor;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 토큰·지연 계측 — 보이지 않는 비용은 줄일 수 없다.
 *
 * 전체 구간을 감싸도록 바깥쪽(order 10)에 둔다.
 * 안쪽에 두면 검색·메모리 주입에 걸린 시간이 측정에서 빠진다.
 */
@Component
public class TokenMeterAdvisor implements CallAdvisor, StreamAdvisor {

    public static final int ORDER = 10;

    private final MeterRegistry registry;

    public TokenMeterAdvisor(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long started = System.nanoTime();
        ChatClientResponse response = chain.nextCall(request);
        recordLatency(started, "call");
        recordTokens(response);
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        long started = System.nanoTime();
        AtomicLong firstTokenNanos = new AtomicLong();
        return chain.nextStream(request)
                .doOnNext(response -> {
                    // 사용자가 체감하는 속도는 전체 시간이 아니라 첫 토큰까지의 시간이 정한다
                    if (firstTokenNanos.compareAndSet(0, System.nanoTime())) {
                        registry.timer("ai.latency", "phase", "first_token")
                                .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                    }
                    recordTokens(response);   // 사용량은 보통 마지막 조각에만 실려 온다
                })
                .doFinally(signal -> recordLatency(started, "stream"));
    }

    private void recordLatency(long startedNanos, String phase) {
        registry.timer("ai.latency", "phase", phase)
                .record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
    }

    private void recordTokens(ChatClientResponse response) {
        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse == null || chatResponse.getMetadata() == null) {
            return;
        }
        Usage usage = chatResponse.getMetadata().getUsage();
        if (usage == null) {
            return;
        }
        increment("prompt", usage.getPromptTokens());
        increment("completion", usage.getCompletionTokens());
    }

    private void increment(String type, Integer tokens) {
        if (tokens != null && tokens > 0) {
            // feature 태그를 붙여야 나중에 기능별로 쪼개 볼 수 있다
            registry.counter("ai.tokens", "type", type, "feature", "chat").increment(tokens);
        }
    }

    @Override
    public String getName() {
        return "tokenMeter";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
