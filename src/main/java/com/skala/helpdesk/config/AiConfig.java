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
     * <p>여기에는 Advisor 를 걸지 않는다. 폴백은 이미 실패한 경로의 마지막 시도이므로
     * 검색·메모리까지 다시 태우면 실패 지점만 늘어난다. 대화 이력은 호출부가
     * conversationId 파라미터로 이어 붙인다.
     *
     * <p>중요한 것은 어떤 모델이냐가 아니라 <b>주 모델과 다른 모델</b>이라는 점이다.
     * 같은 모델로 폴백하면 주 모델을 죽인 원인이 폴백도 그대로 죽인다.
     */
    @Bean
    ChatClient fallbackChatClient(ChatClient.Builder builder, HelpDeskProperties props) {
        return builder.clone()
                // 2.0 에서 defaultOptions 는 ChatOptions 가 아니라 그 Builder 를 받는다
                .defaultOptions(ChatOptions.builder()
                        .model(props.fallback().model()))
                .build();
    }

    public static final int SAFE_GUARD_ORDER = AdvisorOrder.SENSITIVE_INPUT;
    public static final int CHAT_MEMORY_ORDER = AdvisorOrder.CHAT_MEMORY;
    public static final int QUESTION_ANSWER_ORDER = AdvisorOrder.QUESTION_ANSWER;
}
