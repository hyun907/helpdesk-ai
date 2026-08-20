package com.skala.helpdesk.config;

import com.skala.helpdesk.advisor.AuditAdvisor;
import com.skala.helpdesk.advisor.TokenMeterAdvisor;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
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
import org.springframework.core.io.Resource;

/**
 * AI 계층 조립 — 모델·옵션·공통 관심사가 모이는 한 곳.
 *
 * 여기서 조립한 기본값 덕분에 호출부(Service)는 질문과 대화 ID 만 넘기면 된다.
 * 프롬프트를 고쳐도 업무 코드는 그대로다.
 */
@Configuration
@EnableConfigurationProperties(HelpDeskProperties.class)
public class AiConfig {

    /** 안전 필터가 차단할 민감어. 값이 아니라 정책이므로 코드가 아닌 설정에 가깝게 둔다. */
    private static final List<String> SENSITIVE_WORDS =
            List.of("주민등록번호", "주민번호", "카드번호", "계좌번호", "비밀번호");

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
                                  @Value("classpath:/prompts/system.st") Resource systemPrompt) {
        return builder
                .defaultSystem(systemPrompt)
                .defaultAdvisors(
                        audit,
                        meter,
                        SafeGuardAdvisor.builder()
                                .sensitiveWords(SENSITIVE_WORDS)
                                .order(SAFE_GUARD_ORDER)
                                .build(),
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

    public static final int SAFE_GUARD_ORDER = 100;
    public static final int CHAT_MEMORY_ORDER = 200;
    public static final int QUESTION_ANSWER_ORDER = 300;
}
