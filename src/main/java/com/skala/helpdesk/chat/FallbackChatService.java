package com.skala.helpdesk.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 모델 폴백 — 주 모델이 죽어도 상담 화면은 살아 있어야 한다.
 *
 * <p><b>왜 이 클래스가 있는가.</b> 모델 호출은 우리가 통제할 수 없는 남의 서비스다.
 * 429 와 5xx 는 정상 운영 중에도 나온다. 이 예외를 그대로 컨트롤러까지 올려 보내면
 * 사용자는 500 화면을 보고, 방금 쓴 문의 내용을 잃는다. 답을 못 주는 것과
 * 화면이 죽는 것은 전혀 다른 사고다. 앞의 것은 불편이고 뒤의 것은 장애다.
 * 그래서 이 경로는 <b>어떤 경우에도 예외를 던지지 않고 문자열을 돌려준다.</b>
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

    /**
     * 보조 모델은 {@link ObjectProvider} 로 받는다.
     * 필수 의존으로 걸면 보조 모델 빈이 없는 환경에서 애플리케이션 자체가 뜨지 않는다.
     * 장애에 대비하려고 넣은 코드가 평상시 기동을 막는 것은 앞뒤가 바뀐 일이다.
     */
    public FallbackChatService(PrimaryChatCaller primaryCaller,
                               @Qualifier("fallbackChatClient") ObjectProvider<ChatClient> secondary) {
        this.primaryCaller = primaryCaller;
        this.secondary = secondary;
    }

    /** 이 메서드는 예외를 던지지 않는다. 던지는 순간 막으려던 500 화면이 그대로 돌아온다. */
    public String answer(String question, String conversationId) {
        try {
            return primaryCaller.call(question, conversationId);   // ① 재시도 포함
        }
        catch (Exception primaryFailure) {
            return degrade(primaryFailure, question, conversationId);
        }
    }

    /** ② 보조 모델 → ③ 정형 응답 순으로 내려간다. */
    private String degrade(Exception failure, String question, String conversationId) {
        log.warn("주 모델 호출 실패 — 보조 모델로 전환한다. conversationId={} cause={}",
                conversationId, failure.getClass().getSimpleName(), failure);

        ChatClient backup = secondary.getIfAvailable();
        if (backup == null) {
            // 설정 누락이지 실행 오류가 아니다. 사용자 응답은 살리고 운영자에게만 알린다.
            log.error("보조 모델 빈(fallbackChatClient)이 없다. 정형 응답으로 내려간다");
            return DEGRADED_ANSWER;
        }

        try {
            String content = call(backup, question, conversationId);
            if (StringUtils.hasText(content)) {
                log.info("보조 모델로 응답했다. conversationId={}", conversationId);
                return content;
            }
            log.error("보조 모델도 빈 응답을 돌려줬다. 정형 응답으로 내려간다");
        }
        catch (Exception e) {
            // 폴백이 실패해서 장애가 되는 일은 없어야 한다. 여기서 끊는다.
            log.error("보조 모델도 실패했다. 정형 응답으로 내려간다. conversationId={}", conversationId, e);
        }
        return DEGRADED_ANSWER;
    }

    /**
     * 대화 ID 를 그대로 넘겨 폴백 응답도 같은 대화에 이어 붙게 한다.
     * 폴백 응답만 이력에서 빠지면 다음 턴의 문맥에 구멍이 생겨,
     * 사용자는 "방금 한 말을 못 알아듣는" 상담을 받게 된다.
     */
    private String call(ChatClient client, String question, String conversationId) {
        return client.prompt()
                .user(question)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
    }
}
