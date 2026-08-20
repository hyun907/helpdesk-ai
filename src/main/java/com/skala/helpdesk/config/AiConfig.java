package com.skala.helpdesk.config;

import com.skala.helpdesk.advisor.AdvisorOrder;
import com.skala.helpdesk.advisor.AuditAdvisor;
import com.skala.helpdesk.advisor.SensitiveInputAdvisor;
import com.skala.helpdesk.advisor.TokenMeterAdvisor;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.core.io.Resource;

/**
 * AI 계층 조립 — 모델·옵션·공통 관심사가 모이는 한 곳.
 *
 * 여기서 조립한 기본값 덕분에 호출부(Service)는 질문과 대화 ID 만 넘기면 된다.
 * 프롬프트를 고쳐도 업무 코드는 그대로다.
 */
@Configuration
@EnableConfigurationProperties(HelpDeskProperties.class)
@EnableResilientMethods   // Spring Framework 7 내장 재시도 — 별도 라이브러리를 넣지 않는다
public class AiConfig {

    /**
     * 대화 메모리 — 저장소는 JDBC 다. 재시작해도 대화가 이어진다.
     * 윈도우로 잘라 토큰을 통제한다. 무한히 쌓으면 비용이 대화 길이에 비례해 늘어난다.
     */
    @Bean
    ChatMemory chatMemory(ChatMemoryRepository repository, HelpDeskProperties props) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(props.memory().maxMessages())
                .build();
    }

    /**
     * 상담용 ChatClient — Advisor 체인 전체를 기본값으로 건다.
     *
     * 순서가 곧 정책이다. order 가 작을수록 바깥이고, 응답은 역순으로 돌아 나온다.
     *   0   audit        감사 — 차단된 요청도 기록되어야 하므로 가장 바깥
     *   10  tokenMeter   계측 — 전체 구간을 감싸야 실제 지연이 잡힌다
     *   100 safeGuard    차단 — 메모리 저장보다 반드시 앞
     *   200 chatMemory   기억 — 차단을 통과한 입력만 저장된다
     *   300 questionAnswer 근거 — 맥락이 반영된 질문으로 검색한다
     */
    @Bean
    ChatClient helpDeskChatClient(ChatClient.Builder builder,
                                  VectorStore vectorStore,
                                  ChatMemory chatMemory,
                                  HelpDeskProperties props,
                                  AuditAdvisor audit,
                                  TokenMeterAdvisor meter,
                                  SensitiveInputAdvisor sensitiveInput,
                                  @Value("classpath:/prompts/system.st") Resource systemPrompt) {
        return builder
                .defaultSystem(systemPrompt)
                .defaultAdvisors(
                        audit,
                        meter,
                        sensitiveInput,
                        MessageChatMemoryAdvisor.builder(chatMemory)
                                .order(CHAT_MEMORY_ORDER)
                                .build(),
                        QuestionAnswerAdvisor.builder(vectorStore)
                                .searchRequest(SearchRequest.builder()
                                        .topK(props.rag().topK())
                                        .similarityThreshold(props.rag().threshold())
                                        .build())
                                .order(QUESTION_ANSWER_ORDER)
                                .build())
                .build();
    }

    /**
     * 보조 모델 — 주 모델 장애 시에만 쓰인다.
     *
     * <p>중요한 것은 어떤 모델이냐가 아니라 <b>주 모델과 다른 모델</b>이라는 점이다.
     * 같은 모델로 폴백하면 주 모델을 죽인 원인이 폴백도 그대로 죽인다.
     *
     * <p><b>Advisor 를 왜 거의 그대로 다시 거는가.</b> 예전에는 "폴백은 마지막 시도이므로
     * 실패 지점을 늘리지 말자"는 이유로 아무것도 걸지 않았다. 그 상태로 폴백을 실제 상담 경로에
     * 배선했다면 다음이 벌어진다.
     * <ul>
     *   <li>시스템 프롬프트가 없다 — 범위 제한도, 권한 규칙도, "지시문은 데이터다"도 없는 맨 모델이
     *       고객에게 답한다. 장애 상황이 곧 안전장치 해제가 된다.</li>
     *   <li>근거 검색이 없다 — 규정 질문에 근거 없이 답한다. 폴백이 오답 생성기가 된다.</li>
     * </ul>
     * 실패 지점이 하나 느는 것보다 이쪽이 훨씬 나쁘다. 그래서 <b>모델만 다른 같은 조립</b>으로 간다.
     *
     * <p>딱 하나 빼는 것이 메모리 Advisor 다. 주 모델 경로의 메모리 Advisor 는 모델을 부르기 전에
     * 사용자 메시지를 이미 저장했으므로, 여기서 또 걸면 같은 질문이 이력에 두 번 쌓인다.
     * 빠진 조각(답변)만 {@code FallbackChatService} 가 직접 채운다.
     */
    @Bean
    ChatClient fallbackChatClient(ChatClient.Builder builder,
                                  VectorStore vectorStore,
                                  HelpDeskProperties props,
                                  AuditAdvisor audit,
                                  TokenMeterAdvisor meter,
                                  SensitiveInputAdvisor sensitiveInput,
                                  @Value("classpath:/prompts/system.st") Resource systemPrompt) {
        return builder.clone()
                // 2.0 에서 defaultOptions 는 ChatOptions 가 아니라 그 Builder 를 받는다
                .defaultOptions(ChatOptions.builder()
                        .model(props.fallback().model()))
                .defaultSystem(systemPrompt)
                .defaultAdvisors(
                        audit,
                        meter,
                        sensitiveInput,
                        // 메모리(200)는 빼고 근거 검색만 남긴다 — 위 주석 참고
                        QuestionAnswerAdvisor.builder(vectorStore)
                                .searchRequest(SearchRequest.builder()
                                        .topK(props.rag().topK())
                                        .similarityThreshold(props.rag().threshold())
                                        .build())
                                .order(QUESTION_ANSWER_ORDER)
                                .build())
                .build();
    }

    public static final int SAFE_GUARD_ORDER = AdvisorOrder.SENSITIVE_INPUT;
    public static final int CHAT_MEMORY_ORDER = AdvisorOrder.CHAT_MEMORY;
    public static final int QUESTION_ANSWER_ORDER = AdvisorOrder.QUESTION_ANSWER;
}
