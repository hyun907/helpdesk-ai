package com.skala.helpdesk.advisor;

import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 민감어 차단 — <b>사용자가 보낸 메시지만</b> 검사한다.
 *
 * <p>직접 만든 이유가 있다. 기본 제공되는 SafeGuardAdvisor 는 프롬프트 전체를 검사한다.
 * 그런데 우리 시스템 프롬프트에는 "주민등록번호는 상담에서 요구하지 않는다" 같은
 * <b>정책 문장</b>이 들어 있다. 지키라고 적어 둔 규칙에 금지어가 포함돼 있으니,
 * 사용자가 무엇을 묻든 모든 요청이 차단됐다. 실제로 이 프로젝트에서 그 일이 일어났고,
 * 증상은 "모든 질문에 안전 문구만 돌아옴"이라 원인을 프롬프트에서 찾기 어려웠다.
 *
 * <p>차단해야 하는 것은 <b>사용자 입력</b>이지 우리 지침이 아니다.
 * 그래서 검사 범위를 UserMessage 로 좁힌다. 근거 문서와 도구 결과도 검사하지 않는다 —
 * 규정 문서에 "카드번호" 라는 단어가 나온다고 상담을 막을 이유가 없다.
 *
 * <p>order 는 100 이다. 대화 메모리(200)보다 앞이어야 차단된 문장이 이력에 남지 않는다.
 * 뒤에 두면 막았어야 할 문장이 이미 저장된 뒤이고, 그건 로그로도 알 수 없다.
 */
@Component
public class SensitiveInputAdvisor implements CallAdvisor, StreamAdvisor {

    public static final int ORDER = AdvisorOrder.SENSITIVE_INPUT;

    private static final Logger log = LoggerFactory.getLogger(SensitiveInputAdvisor.class);

    /** 상담에서 오갈 이유가 없는 값들. 받는 순간 로그·대화 이력에 남기 때문에 입구에서 끊는다. */
    private static final List<String> SENSITIVE_WORDS = List.of(
            "주민등록번호", "주민번호", "카드번호", "계좌번호", "비밀번호", "cvc", "cvv");

    static final String BLOCKED_MESSAGE = """
            개인 식별번호나 결제 수단 정보는 상담으로 받지 않습니다. \
            해당 내용을 빼고 다시 문의해 주시기 바랍니다.""";

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String hit = findSensitiveWord(request);
        if (hit != null) {
            return blockedResponse(request, hit);
        }
        return chain.nextCall(request);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        String hit = findSensitiveWord(request);
        if (hit != null) {
            return Flux.just(blockedResponse(request, hit));
        }
        return chain.nextStream(request);
    }

    /** 사용자 메시지만 훑는다. 시스템 메시지·근거 문서·도구 결과는 검사 대상이 아니다. */
    private String findSensitiveWord(ChatClientRequest request) {
        for (UserMessage message : request.prompt().getUserMessages()) {
            String text = message.getText();
            if (text == null) {
                continue;
            }
            String normalized = text.toLowerCase(Locale.ROOT);
            for (String word : SENSITIVE_WORDS) {
                if (normalized.contains(word.toLowerCase(Locale.ROOT))) {
                    return word;
                }
            }
        }
        return null;
    }

    /**
     * 차단 사실은 로그에 남기되 <b>입력 원문은 남기지 않는다.</b>
     * 민감정보를 막겠다면서 그 값을 로그에 적으면 막은 의미가 없다.
     * 로그는 대개 보존 기간이 길고 접근 범위가 넓다.
     */
    private ChatClientResponse blockedResponse(ChatClientRequest request, String matchedWord) {
        log.warn("민감어 감지로 요청을 차단했다. 유형={}", matchedWord);
        ChatResponse blocked = new ChatResponse(
                List.of(new Generation(new AssistantMessage(BLOCKED_MESSAGE))));
        return ChatClientResponse.builder()
                .chatResponse(blocked)
                .context(request.context())
                .build();
    }

    @Override
    public String getName() {
        return "sensitiveInput";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
