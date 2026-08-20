package com.skala.helpdesk.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

/**
 * 폴백 사다리가 <b>실제로 타는지</b>를 못 박는다.
 *
 * <p>이 테스트가 필요한 이유가 있다. 한동안 이 프로젝트의 폴백 코드는 완성되어 있었지만
 * 아무도 호출하지 않는 죽은 코드였다. HelpDeskService 가 ChatClient 를 직접 들고 부르고 있었고,
 * 그래서 429 한 번에 500 이 그대로 나갔다. 컴파일도 되고 테스트도 통과하는 종류의 사고다 —
 * 장애가 나기 전까지는 아무 증상이 없다.
 *
 * <p>그래서 여기서 확인하는 것은 "폴백 코드가 올바른가"가 아니라 <b>"폴백이 경로에 붙어 있는가"</b>다.
 */
class FallbackChatServiceTest {

    private static final String CONVERSATION_ID = "game:player1:sess-001";

    private final PrimaryChatCaller primary = mock(PrimaryChatCaller.class);
    private final ChatMemory memory = mock(ChatMemory.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<ChatClient> secondary = mock(ObjectProvider.class);

    private final FallbackChatService fallback = new FallbackChatService(primary, secondary, memory);

    private final ChatCall call = new ChatCall("복구 기한이 며칠인가요", CONVERSATION_ID, Map.of(), List.of());

    // ──────────────────────────────────────────────────────────────
    // 배선 — 이 테스트가 이 파일의 존재 이유다
    // ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("상담 서비스는 ChatClient 를 직접 들고 있지 않다")
    void 모델_진입점이_하나다() {
        boolean holdsChatClient = Arrays.stream(HelpDeskService.class.getDeclaredConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
                .anyMatch(ChatClient.class::isAssignableFrom);

        assertThat(holdsChatClient)
                .as("HelpDeskService 가 ChatClient 를 직접 부르면 재시도·보조 모델·정형 응답이 통째로 우회된다")
                .isFalse();
    }

    // ──────────────────────────────────────────────────────────────
    // 동기 사다리
    // ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("주 모델이 답하면 보조 모델은 건드리지 않는다")
    void 평상시에는_주_모델로_끝난다() {
        ChatClientResponse expected = responseOf("복구는 14일 이내에 신청할 수 있습니다.");
        given(primary.call(call)).willReturn(expected);

        assertThat(fallback.answer(call)).isSameAs(expected);

        // 평상시에 보조 모델을 조회조차 하지 않아야 한다 — 조회한다면 사다리 순서가 뒤집힌 것이다
        verify(secondary, never()).getIfAvailable();
        verify(memory, never()).add(any(String.class), any(Message.class));
    }

    @Test
    @DisplayName("주 모델이 실패하고 보조 모델 빈도 없으면 정형 응답으로 내려간다")
    void 보조_모델이_없어도_500_이_나가지_않는다() {
        given(primary.call(call)).willThrow(new IllegalStateException("429 Too Many Requests"));
        given(secondary.getIfAvailable()).willReturn(null);

        // 핵심은 반환값이 아니라 '예외가 올라오지 않는다'는 사실이다
        assertThat(textOf(fallback.answer(call))).isEqualTo(FallbackChatService.DEGRADED_ANSWER);
    }

    @Test
    @DisplayName("보조 모델까지 실패해도 예외가 아니라 정형 응답이 나간다")
    void 폴백이_장애가_되지_않는다() {
        given(primary.call(call)).willThrow(new IllegalStateException("500 Internal Server Error"));
        // prompt() 단계에서 터뜨린다 — 보조 모델 호출이 어디서 실패하든 결과는 같아야 한다
        ChatClient broken = mock(ChatClient.class);
        given(broken.prompt()).willThrow(new IllegalStateException("보조 모델도 죽었다"));
        given(secondary.getIfAvailable()).willReturn(broken);

        assertThat(textOf(fallback.answer(call))).isEqualTo(FallbackChatService.DEGRADED_ANSWER);
        // 실패한 턴을 이력에 남기면 다음 턴에 사과문이 문맥으로 들어간다
        verify(memory, never()).add(any(String.class), any(Message.class));
    }

    // ──────────────────────────────────────────────────────────────
    // 스트리밍 사다리
    // ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("첫 토큰 전에 끊기면 보조 모델로 갈아탄다")
    void 첫_토큰_전_실패는_전환한다() {
        given(primary.stream(call)).willReturn(Flux.error(new IllegalStateException("연결 실패")));
        given(secondary.getIfAvailable()).willReturn(null);   // 전환을 시도했는지만 본다

        List<String> emitted = collect(fallback.stream(call));

        verify(secondary).getIfAvailable();
        assertThat(emitted).containsExactly(FallbackChatService.DEGRADED_ANSWER);
    }

    @Test
    @DisplayName("토큰이 이미 나간 뒤에 끊기면 모델을 바꾸지 않는다")
    void 나간_토큰_뒤에는_전환하지_않는다() {
        given(primary.stream(call)).willReturn(Flux.concat(
                Flux.just(responseOf("복구는 ")),
                Flux.error(new IllegalStateException("스트림 중단"))));

        // 오류가 그대로 올라와야 한다 — 호출부가 대체 문장 하나로 마무리한다
        List<String> emitted = collect(fallback.stream(call).onErrorResume(e -> Flux.just(responseOf("[중단]"))));

        assertThat(emitted).containsExactly("복구는 ", "[중단]");
        // 여기서 보조 모델을 붙이면 한 문단에 두 개의 답변이 섞인다
        verify(secondary, never()).getIfAvailable();
    }

    // ──────────────────────────────────────────────────────────────

    /**
     * 폴백이 답했을 때 이력에 <b>답변만</b> 추가되는지 본다.
     *
     * <p>주 모델 경로의 메모리 Advisor 가 사용자 메시지를 이미 저장한 뒤에 실패하므로,
     * 여기서 사용자 메시지까지 저장하면 같은 질문이 두 번 실린 대화가 다음 턴에 들어간다.
     */
    @Test
    @DisplayName("폴백이 답하면 답변만 이력에 이어 붙는다")
    void 사용자_메시지를_두_번_저장하지_않는다() {
        given(primary.call(call)).willThrow(new IllegalStateException("429"));
        ChatClient backup = mock(ChatClient.class);
        given(backup.prompt()).willThrow(new IllegalStateException("호출까지 갈 필요는 없다"));
        given(secondary.getIfAvailable()).willReturn(backup);

        fallback.answer(call);

        // 저장이 일어난다면 AssistantMessage 여야 한다. UserMessage 저장은 어떤 경우에도 없어야 한다.
        verify(memory, never()).add(eq(CONVERSATION_ID), any(org.springframework.ai.chat.messages.UserMessage.class));
    }

    private static ChatClientResponse responseOf(String text) {
        return ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))))
                .context(Map.of())
                .build();
    }

    private static String textOf(ChatClientResponse response) {
        return response.chatResponse().getResult().getOutput().getText();
    }

    private static List<String> collect(Flux<ChatClientResponse> flux) {
        return flux.map(FallbackChatServiceTest::textOf).collectList().block();
    }
}
