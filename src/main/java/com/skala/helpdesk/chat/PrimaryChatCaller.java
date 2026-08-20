package com.skala.helpdesk.chat;

import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.RateLimitException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;

/**
 * 주 모델 호출 + 재시도.
 *
 * <p><b>왜 별도 빈인가.</b> 재시도는 프록시로 걸린다. 같은 클래스 안에서 메서드를 부르면
 * 프록시를 거치지 않으므로 재시도가 조용히 사라진다. FallbackChatService 안에 두고
 * this.call(...) 로 불렀다면 애노테이션은 붙어 있지만 아무 일도 하지 않는다.
 * 그래서 호출 주체와 재시도 대상을 다른 빈으로 갈라 둔다.
 *
 * <p>재시도는 Spring Framework 7 내장 기능을 쓴다. 별도 라이브러리를 넣지 않는다.
 */
@Component
public class PrimaryChatCaller {

    /**
     * maxRetries 는 <b>최초 호출 이후의 추가 시도 횟수</b>다. 1 이면 총 2회 호출한다.
     * 더 키우지 않는 이유는 사용자가 화면 앞에서 기다리기 때문이다.
     * 모델 한 번 호출이 수 초인데 3~4회를 돌면 폴백이 오는 편이 낫다.
     */
    private static final long MAX_RETRIES = 1;

    /** 재시도 간격. 상대가 잠깐 숨을 돌릴 만큼은 되고, 사람이 체감하기엔 짧은 값. */
    private static final long DELAY_MILLIS = 500L;

    private final ChatClient primary;

    public PrimaryChatCaller(@Qualifier("helpDeskChatClient") ChatClient primary) {
        this.primary = primary;
    }

    /**
     * 재시도 대상을 화이트리스트로 못 박는다. 429·5xx·네트워크 끊김만 다시 부른다.
     * 401(키 오류)이나 400(요청 오류)은 몇 번을 불러도 같은 답이 온다.
     * 그런 것까지 재시도하면 사용자를 기다리게 만든 대가로 같은 실패를 두 번 받을 뿐이다.
     *
     * <p>목록에 없는 예외는 재시도 없이 그대로 던져진다. 호출부가 폴백으로 넘긴다.
     */
    @Retryable(
            includes = {
                    RateLimitException.class,       // 429 — 잠시 뒤면 풀린다
                    InternalServerException.class,  // 5xx — 상대 쪽 일시 장애
                    OpenAIIoException.class,        // 연결 끊김·타임아웃
                    ResourceAccessException.class   // 우리 쪽 HTTP 클라이언트가 감싼 I/O 오류
            },
            maxRetries = MAX_RETRIES,
            delay = DELAY_MILLIS)
    public String call(String question, String conversationId) {
        String content = primary.prompt()
                .user(question)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();

        if (!StringUtils.hasText(content)) {
            // 예외 없이 빈 응답이 오는 경우가 있다. 빈 문자열을 화면에 그대로 내보내면
            // 사용자는 무엇이 잘못됐는지조차 알 수 없다. 여기서 폴백 경로로 넘긴다.
            throw new EmptyAnswerException("주 모델이 빈 응답을 돌려줬다");
        }
        return content;
    }

    /** 빈 응답을 폴백 경로에 태우기 위한 내부 신호. 재시도 목록에 없으므로 바로 폴백으로 간다. */
    public static class EmptyAnswerException extends RuntimeException {

        public EmptyAnswerException(String message) {
            super(message);
        }
    }
}
