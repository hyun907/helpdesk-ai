package com.skala.helpdesk.chat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

/**
 * 모델 폴백 — 주 모델이 죽어도 상담 화면은 살아 있어야 한다.
 *
 * <p><b>왜 이 클래스가 있는가.</b> 모델 호출은 우리가 통제할 수 없는 남의 서비스다.
 * 429 와 5xx 는 정상 운영 중에도 나온다. 이 예외를 그대로 컨트롤러까지 올려 보내면
 * 사용자는 500 화면을 보고, 방금 쓴 문의 내용을 잃는다. 답을 못 주는 것과
 * 화면이 죽는 것은 전혀 다른 사고다. 앞의 것은 불편이고 뒤의 것은 장애다.
 * 그래서 이 경로는 <b>어떤 경우에도 예외를 던지지 않고 응답을 돌려준다.</b>
 *
 * <p>단계는 셋이다.
 * <ol>
 *   <li>재시도 — 일시적 오류에 한해 짧은 백오프로 다시 부른다 (PrimaryChatCaller)</li>
 *   <li>보조 모델 — 다른 ChatClient 빈으로 한 번 더 시도한다</li>
 *   <li>정형 응답 — 접수는 되었음을 알리고 끝낸다</li>
 * </ol>
 *
 * <p>사다리를 애노테이션(@Recover) 대신 코드로 명시한 이유는 둘이다.
 * 하나, Spring Framework 7 내장 재시도에는 복구 메서드 개념이 없다.
 * 둘, 어느 단계로 내려갔는지가 코드에 그대로 보이는 편이 읽기도 테스트하기도 낫다.
 *
 * <p>어느 경로를 탔는지는 로그로 남긴다. 폴백이 조용히 동작하면
 * 주 모델이 며칠째 죽어 있는 것을 아무도 모른다. 그것도 장애다.
 *
 * <p><b>이 클래스는 상담 경로의 유일한 모델 진입점이다.</b> HelpDeskService 가 ChatClient 를
 * 직접 부르던 시절에는 이 사다리 전체가 아무도 호출하지 않는 코드였고, 429 한 번에 500 이 나갔다.
 * 모델을 부르는 자리를 여기 하나로 좁혀 두어야 "폴백을 빠뜨린 경로"가 생기지 않는다.
 */
@Service
public class FallbackChatService {

    private static final Logger log = LoggerFactory.getLogger(FallbackChatService.class);

    /**
     * 마지막 단계의 정형 응답.
     *
     * <p>여기에 지키는 규칙이 둘 있다.
     * 하나, 규정 내용을 절대 담지 않는다. 근거 없이 만든 문장이 정답처럼 읽히면
     * 폴백이 오히려 잘못된 안내가 된다.
     * 둘, "접수되었다"고만 말하고 처리·승인은 약속하지 않는다. 지킬 수 없는 말은 하지 않는다.
     */
    static final String DEGRADED_ANSWER = """
            지금은 답변을 드릴 수 없습니다. 문의는 접수되었으며, \
            잠시 후 다시 시도해 주시거나 담당자 확인 후 순차적으로 안내드리겠습니다.""";

    private final PrimaryChatCaller primaryCaller;
    private final ObjectProvider<ChatClient> secondary;
    private final ChatMemory chatMemory;

    /**
     * 보조 모델은 {@link ObjectProvider} 로 받는다.
     * 필수 의존으로 걸면 보조 모델 빈이 없는 환경에서 애플리케이션 자체가 뜨지 않는다.
     * 장애에 대비하려고 넣은 코드가 평상시 기동을 막는 것은 앞뒤가 바뀐 일이다.
     */
    public FallbackChatService(PrimaryChatCaller primaryCaller,
                               @Qualifier("fallbackChatClient") ObjectProvider<ChatClient> secondary,
                               ChatMemory chatMemory) {
        this.primaryCaller = primaryCaller;
        this.secondary = secondary;
        this.chatMemory = chatMemory;
    }

    // ──────────────────────────────────────────────────────────────
    // 동기
    // ──────────────────────────────────────────────────────────────

    /** 이 메서드는 예외를 던지지 않는다. 던지는 순간 막으려던 500 화면이 그대로 돌아온다. */
    public ChatClientResponse answer(ChatCall call) {
        try {
            return primaryCaller.call(call);   // ① 재시도 포함
        }
        catch (Exception primaryFailure) {
            return degrade(primaryFailure, call);
        }
    }

    /** ② 보조 모델 → ③ 정형 응답 순으로 내려간다. */
    private ChatClientResponse degrade(Exception failure, ChatCall call) {
        log.warn("주 모델 호출 실패 — 보조 모델로 전환한다. conversationId={} cause={}",
                call.conversationId(), failure.getClass().getSimpleName(), failure);

        ChatClient backup = secondary.getIfAvailable();
        if (backup == null) {
            // 설정 누락이지 실행 오류가 아니다. 사용자 응답은 살리고 운영자에게만 알린다.
            log.error("보조 모델 빈(fallbackChatClient)이 없다. 정형 응답으로 내려간다");
            return degraded();
        }

        try {
            ChatClientResponse response = call.callOn(backup);
            String content = HelpDeskService.textOf(response);
            if (StringUtils.hasText(content)) {
                rememberAssistantTurn(call.conversationId(), content);
                log.info("보조 모델로 응답했다. conversationId={}", call.conversationId());
                return response;
            }
            log.error("보조 모델도 빈 응답을 돌려줬다. 정형 응답으로 내려간다");
        }
        catch (Exception e) {
            // 폴백이 실패해서 장애가 되는 일은 없어야 한다. 여기서 끊는다.
            log.error("보조 모델도 실패했다. 정형 응답으로 내려간다. conversationId={}", call.conversationId(), e);
        }
        return degraded();
    }

    // ──────────────────────────────────────────────────────────────
    // 스트리밍
    // ──────────────────────────────────────────────────────────────

    /**
     * 스트리밍에서의 폴백은 <b>첫 토큰이 나가기 전까지만</b> 가능하다.
     *
     * <p>토큰이 이미 화면에 찍힌 뒤에 다른 모델의 답을 이어 붙이면 한 문단 안에서
     * 두 개의 답변이 섞인다. 사용자에게는 "말하다 말고 딴소리하는 상담원"으로 보이고,
     * 그건 끊긴 응답보다 나쁘다. 그래서 그 경우에는 폴백하지 않고 오류를 위로 넘겨
     * 호출부가 대체 문장 하나로 마무리하게 둔다.
     *
     * <p>반대로 첫 토큰 전 실패는 대부분 연결·429 다 — 이때는 보조 모델로 통째로 갈아탄다.
     * 사용자는 전환이 있었다는 사실조차 모른다.
     */
    public Flux<ChatClientResponse> stream(ChatCall call) {
        AtomicBoolean emitted = new AtomicBoolean(false);

        return primaryCaller.stream(call)
                .doOnNext(response -> {
                    if (StringUtils.hasText(HelpDeskService.textOf(response))) {
                        emitted.set(true);
                    }
                })
                .onErrorResume(failure -> {
                    if (emitted.get()) {
                        log.warn("스트림 중단 — 이미 나간 토큰이 있어 모델을 바꾸지 않는다. conversationId={} cause={}",
                                call.conversationId(), failure.toString());
                        return Flux.error(failure);
                    }
                    return streamFromSecondary(call, failure);
                });
    }

    private Flux<ChatClientResponse> streamFromSecondary(ChatCall call, Throwable failure) {
        log.warn("첫 토큰 전 스트림 실패 — 보조 모델 스트림으로 전환한다. conversationId={} cause={}",
                call.conversationId(), failure.toString());

        ChatClient backup = secondary.getIfAvailable();
        if (backup == null) {
            log.error("보조 모델 빈(fallbackChatClient)이 없다. 정형 응답으로 내려간다");
            return Flux.just(degraded());
        }
        return call.streamOn(backup)
                .onErrorResume(secondaryFailure -> {
                    log.error("보조 모델 스트림도 실패했다. 정형 응답으로 내려간다. conversationId={}",
                            call.conversationId(), secondaryFailure);
                    return Flux.just(degraded());
                });
    }

    // ──────────────────────────────────────────────────────────────
    // 내부
    // ──────────────────────────────────────────────────────────────

    /**
     * 폴백으로 답한 턴을 대화 이력에 이어 붙인다.
     *
     * <p><b>왜 보조 ChatClient 에 메모리 Advisor 를 걸지 않고 여기서 직접 쓰는가.</b>
     * 주 모델 경로의 MessageChatMemoryAdvisor 는 모델을 부르기 <i>전에</i> 사용자 메시지를 저장한다.
     * 그러니 주 모델이 터진 시점에 사용자 메시지는 이미 이력에 들어가 있고, 빠진 것은 답변 쪽뿐이다.
     * 보조 클라이언트에 메모리 Advisor 를 또 걸면 같은 사용자 메시지가 한 번 더 저장된다 —
     * 다음 턴부터 같은 질문이 두 번 실린 대화가 모델에 들어간다.
     * 그래서 <b>빠진 조각만</b> 채운다.
     *
     * <p>이력 저장이 실패해도 사용자 응답은 살린다. 기록은 부가 기능이고 답변이 본 기능이다.
     */
    private void rememberAssistantTurn(String conversationId, String content) {
        try {
            chatMemory.add(conversationId, new AssistantMessage(content));
        }
        catch (Exception e) {
            log.warn("폴백 응답을 대화 이력에 남기지 못했다 — 다음 턴의 문맥에 구멍이 생긴다. conversationId={}",
                    conversationId, e);
        }
    }

    /**
     * 정형 응답을 {@code ChatClientResponse} 모양으로 만들어 돌려준다.
     *
     * <p>문자열이 아니라 응답 객체로 맞추는 이유는 호출부를 갈라 놓지 않기 위해서다.
     * "성공이면 응답 객체, 실패면 문자열"로 두면 호출부에 분기가 생기고,
     * 그 분기 중 한쪽은 출처·도구 사용 여부를 채우는 코드를 빠뜨리게 된다.
     *
     * <p>근거 목록은 비어 있다 — 이 문장에는 근거가 없기 때문이다. 그 사실이 응답에 그대로 드러나야 한다.
     */
    private static ChatClientResponse degraded() {
        return ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(DEGRADED_ANSWER)))))
                .context(Map.of())
                .build();
    }
}
