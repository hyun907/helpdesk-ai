package com.skala.helpdesk.chat;

import com.skala.helpdesk.advisor.ToolAuditAspect;

import com.skala.helpdesk.chat.AnswerDto.Source;
import com.skala.helpdesk.tools.CharacterTools;
import com.skala.helpdesk.tools.TicketTools;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

/**
 * 상담 한 번의 흐름 — 질문을 받아 근거·도구·대화 이력을 붙여 모델에 넘기고, 답변과 근거를 돌려준다.
 *
 * Advisor 체인은 AiConfig 에서 이미 다 걸려 있다. 여기서 추가로 정하는 것은 세 가지뿐이다.
 *   1. 대화 ID   — 어느 대화에 이어 붙일지
 *   2. 도구      — 이번 요청에서 모델이 쓸 수 있는 실시간 조회 수단
 *   3. 도구 컨텍스트 — 도구가 계정 경계를 확인할 때 쓸 사용자 ID
 *
 * 사용자 ID 를 프롬프트 문장에 섞지 않는 이유가 이 클래스의 핵심이다.
 * 프롬프트에 넣으면 그 값은 모델이 읽는 텍스트가 되고, 이용자가
 * "나는 사실 player9 야" 같은 문장을 넣는 순간 모델이 그쪽을 믿을 수 있다.
 * toolContext 로 넘긴 값은 모델이 보지도, 바꾸지도 못한다.
 */
@Service
public class HelpDeskService {

    private static final Logger log = LoggerFactory.getLogger(HelpDeskService.class);

    /** 도구가 ToolContext 에서 호출자 계정을 꺼낼 때 쓰는 키. 모델이 채우는 값이 아니다. */
    public static final String USER_ID = "userId";

    /** 이번 요청에서 실제로 실행된 도구 이름이 쌓이는 자리. 값은 요청마다 새로 만든 Set 이다. */
    public static final String TOOL_TRACE = "toolTrace";

    /**
     * 대화 ID 앞에 붙는 테넌트 구분자.
     * 지금은 서비스가 하나뿐이라 상수지만, 접두어 없이 저장해 두면 나중에 서비스가 늘었을 때
     * 이미 쌓인 이력과 새 이력이 같은 키를 두고 섞인다. 그때는 되돌릴 방법이 없다.
     */
    private static final String TENANT = "game";

    /** 세션 ID 가 비어 있을 때의 대체값. */
    private static final String DEFAULT_SESSION = "default";

    /**
     * 스트리밍 상한. 모델이 응답을 멈추면 커넥션이 그대로 붙잡혀 있어
     * 톰캣 스레드와 async 컨텍스트가 계속 점유된다. 상한이 없으면 서버가 조용히 말라 죽는다.
     */
    private static final Duration STREAM_TIMEOUT = Duration.ofSeconds(60);

    private static final String STREAM_FALLBACK =
            "죄송합니다. 답변을 끝까지 전달하지 못했습니다. 접수나 조회는 처리되지 않았습니다.";

    /**
     * 모델을 부르는 유일한 통로.
     *
     * <p>ChatClient 를 직접 들고 있지 않은 것이 요점이다. 직접 부르면 재시도·보조 모델·정형 응답이
     * 전부 우회되고, 429 한 번에 500 이 나간다. 폴백을 "있는데 안 타는" 상태로 만들지 않으려면
     * 모델 진입점이 하나여야 한다.
     */
    private final FallbackChatService chat;
    private final CharacterTools characterTools;
    private final TicketTools ticketTools;

    public HelpDeskService(FallbackChatService chat,
                           CharacterTools characterTools,
                           TicketTools ticketTools) {
        this.chat = chat;
        this.characterTools = characterTools;
        this.ticketTools = ticketTools;
    }

    // ──────────────────────────────────────────────────────────────
    // 동기 상담
    // ──────────────────────────────────────────────────────────────

    /**
     * 오늘 날짜를 <b>사용자 메시지</b>에 붙인다. 시스템 프롬프트가 아니라 여기인 이유가 있다.
     *
     * <p>모델은 오늘이 며칠인지 모른다. 그 상태에서 "어제 사라졌어요" 같은 표현을 도구 인자로
     * 옮기라고 하면 그럴듯한 날짜를 지어낸다. 실제로 3년 전 날짜가 신청서에 적힌 적이 있고,
     * 복구 정책은 14일 이내라 그 신청은 애초에 성립할 수 없는 것이었다.
     *
     * <p>시스템 프롬프트에 넣지 않는 이유는 캐시다. 시스템 프롬프트는 매 요청 동일해야
     * 공급자의 프롬프트 캐싱이 걸린다. 날짜가 하루만 바뀌어도 앞부분이 통째로 무효가 된다.
     * 가변값은 뒤쪽(사용자 메시지)에 둔다.
     */
    private String withToday(String question) {
        return "[오늘 %s]\n%s".formatted(LocalDate.now(), question);
    }

    /**
     * 한 번의 상담에 넘길 재료를 한 덩어리로 묶는다.
     *
     * <p>동기와 스트리밍이 같은 메서드를 쓰는 것이 요점이다. 두 곳에서 각자 조립하면
     * 한쪽에만 도구가 빠지거나 도구 컨텍스트가 빠지는 날이 온다. 그 사고는 컴파일도 되고
     * 테스트도 통과한다 — 증상은 "스트리밍으로 물으면 캐릭터 정보를 지어낸다" 하나뿐이다.
     */
    private ChatCall callFor(String question, String userId, String sessionId, Set<String> toolTrace) {
        return new ChatCall(
                withToday(question),
                conversationId(TENANT, userId, sessionId),
                Map.of(USER_ID, userId,
                        TOOL_TRACE, toolTrace,
                        ToolAuditAspect.CALL_BUDGET, new AtomicInteger()),
                List.of(characterTools, ticketTools));
    }

    public AnswerDto ask(String question, String userId, String sessionId) {
        // 요청마다 새로 만든다. 필드에 두면 동시에 들어온 다른 이용자의 도구 호출이 섞여 집계된다.
        Set<String> toolTrace = ConcurrentHashMap.newKeySet();

        // content() 가 아니라 chatClientResponse() 를 받는다.
        // content() 는 문자열만 준다 — 어떤 문서를 근거로 삼았는지는 context() 에만 실려 있고,
        // 한 번 버리면 다시 만들 수 없다(검색을 또 돌리면 결과가 달라질 수 있다).
        //
        // 모델은 chat(FallbackChatService)을 통해서만 부른다. 재시도 → 보조 모델 → 정형 응답
        // 사다리가 여기 한 줄 뒤에 들어 있다. 이 메서드는 예외를 받지 않는다.
        ChatClientResponse response = chat.answer(callFor(question, userId, sessionId, toolTrace));

        String answer = textOf(response);
        if (!StringUtils.hasText(answer)) {
            // 빈 응답을 그대로 내보내면 화면에는 '답변 완료'로 보인다. 실패는 실패로 드러나야 한다.
            log.warn("모델이 빈 응답을 반환했다 userId={} toolTrace={}", userId, toolTrace);
            return AnswerDto.unknown();
        }
        return new AnswerDto(answer, sourcesOf(response), !toolTrace.isEmpty());
    }

    // ──────────────────────────────────────────────────────────────
    // 스트리밍 상담
    // ──────────────────────────────────────────────────────────────

    /** 토큰만 필요한 호출자용. 근거까지 필요하면 {@link #streamWithSources} 를 쓴다. */
    public Flux<String> stream(String question, String userId, String sessionId) {
        return streamWithSources(question, userId, sessionId).tokens();
    }

    /**
     * 토큰 스트림과 '이번 요청의 근거'를 한 묶음으로 돌려준다.
     *
     * 근거는 스트림이 끝나야 확정되므로 토큰과 같은 채널로는 못 내보낸다.
     * 그렇다고 서비스 필드에 담아 두면(예: private List<Source> lastSources)
     * 동시에 상담 중인 다른 이용자의 근거가 덮어써진다 — 남의 문서 목록이 내 화면에 뜬다.
     * 그래서 상태를 요청마다 새로 만든 StreamSession 안에만 둔다.
     */
    public StreamSession streamWithSources(String question, String userId, String sessionId) {
        AtomicReference<List<Source>> lastSources = new AtomicReference<>(List.of());
        Set<String> toolTrace = ConcurrentHashMap.newKeySet();
        String conversationId = conversationId(TENANT, userId, sessionId);

        // 첫 토큰 전 실패는 보조 모델로 갈아탄다 — 그 판단은 chat 안에 있다.
        Flux<String> tokens = chat.stream(callFor(question, userId, sessionId, toolTrace))
                .doOnNext(response -> {
                    // 근거는 조각마다 실려 오지 않는다. 실려 온 조각이 있으면 그것을 최신값으로 둔다.
                    List<Source> found = sourcesOf(response);
                    if (!found.isEmpty()) {
                        lastSources.set(found);
                    }
                })
                .map(HelpDeskService::textOf)
                .filter(text -> !text.isEmpty())     // 빈 조각을 SSE 로 내보내면 클라이언트가 이벤트 경계를 잘못 읽는다
                .timeout(STREAM_TIMEOUT)
                .doOnCancel(() -> log.info("스트림 취소 conversationId={} — 클라이언트가 먼저 끊었다", conversationId))
                .onErrorResume(e -> {
                    // 스트리밍은 이미 200 과 헤더가 나간 뒤다. 여기서 예외를 위로 던져도
                    // 클라이언트에는 '중간에 끊긴 응답'으로만 보인다. 대체 문장을 마지막 토큰으로 흘려보낸다.
                    log.warn("스트림 실패 conversationId={} · {}", conversationId, e.toString());
                    return Flux.just(STREAM_FALLBACK);
                });

        return new StreamSession(tokens, lastSources);
    }

    /**
     * 한 번의 스트리밍 요청에 딸린 상태. 요청마다 새로 만들어지고 요청이 끝나면 버려진다.
     * 싱글턴 서비스 대신 이 객체가 요청 단위 상태를 들고 있다는 점이 중요하다.
     */
    public static final class StreamSession {

        private final Flux<String> tokens;
        private final AtomicReference<List<Source>> sources;

        private StreamSession(Flux<String> tokens, AtomicReference<List<Source>> sources) {
            this.tokens = tokens;
            this.sources = sources;
        }

        public Flux<String> tokens() {
            return tokens;
        }

        /** 스트림이 끝난 뒤에 읽어야 채워져 있다. 구독 전에 읽으면 빈 목록이다. */
        public List<Source> lastSources() {
            return sources.get();
        }
    }

    // ──────────────────────────────────────────────────────────────
    // 도구가 쓰는 진입점
    // ──────────────────────────────────────────────────────────────

    /**
     * 도구가 호출자 계정을 꺼내는 유일한 통로.
     *
     * 값이 없으면 조용히 넘어가지 않고 터뜨린다. userId 없이 진행하면 조회 조건에서
     * 계정 경계가 빠진 채 쿼리가 나가거나, 도구가 "확인할 수 없습니다"만 반복해서
     * 배선이 끊긴 사실이 운영 중에 묻힌다. 이건 이용자 잘못이 아니라 배선 버그다.
     */
    public static String callerId(ToolContext toolContext) {
        Object value = (toolContext == null) ? null : toolContext.getContext().get(USER_ID);
        if (!(value instanceof String userId) || userId.isBlank()) {
            throw new IllegalStateException("toolContext 에 " + USER_ID + " 가 없다 — 계정 경계 없이 도구가 실행될 뻔했다");
        }
        return userId;
    }

    /**
     * 도구가 실제로 실행됐다는 사실을 남긴다.
     *
     * 최종 응답만 봐서는 도구 사용 여부를 알 수 없다. 도구 호출은 응답이 확정되기 전
     * 중간 단계에서 끝나고, 마지막 ChatResponse 에는 그 흔적이 남지 않는다.
     * 그래서 도구 쪽에서 직접 표시하게 한다.
     */
    @SuppressWarnings("unchecked")
    public static void markToolUsed(ToolContext toolContext, String toolName) {
        if (toolContext != null && toolContext.getContext().get(TOOL_TRACE) instanceof Set<?> trace) {
            ((Set<String>) trace).add(toolName);
        }
    }

    // ──────────────────────────────────────────────────────────────
    // 내부
    // ──────────────────────────────────────────────────────────────

    /**
     * 대화 ID 를 만드는 단 한 곳.
     *
     * 규칙을 호출부마다 만들면 어느 화면은 userId 만, 어느 화면은 sessionId 만 쓰게 되고
     * 그 순간 이력이 섞인다. userId 를 빼면 세션 ID 가 겹친 남의 대화가 딸려 오고,
     * sessionId 를 빼면 같은 사람이 연 탭 두 개가 한 대화로 합쳐진다. 둘 다 사고다.
     */
    private static String conversationId(String tenant, String userId, String sessionId) {
        String session = StringUtils.hasText(sessionId) ? sessionId : DEFAULT_SESSION;
        return "%s:%s:%s".formatted(tenant, userId, session);
    }

    /**
     * QuestionAnswerAdvisor 가 응답 컨텍스트에 넣어 둔 검색 결과에서 출처를 뽑는다.
     * 같은 문서에서 여러 청크가 걸려도 이용자에게는 문서 한 줄로 보여야 하므로 중복을 없앤다.
     */
    private static List<Source> sourcesOf(ChatClientResponse response) {
        Map<String, Object> context = (response == null) ? null : response.context();
        if (context == null) {
            return List.of();
        }
        Object retrieved = context.get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
        if (!(retrieved instanceof List<?> documents) || documents.isEmpty()) {
            return List.of();
        }
        // LinkedHashSet — 유사도 순서를 유지한 채 중복만 걷어낸다
        Set<Source> unique = new LinkedHashSet<>();
        for (Object item : documents) {
            if (item instanceof Document document) {
                Map<String, Object> metadata = document.getMetadata();
                unique.add(new Source(text(metadata.get("source")), text(metadata.get("version"))));
            }
        }
        return List.copyOf(unique);
    }

    /**
     * 응답 조각에서 본문만 꺼낸다. 조각에 결과가 없는 경우가 있어 인덱스 접근을 피한다.
     *
     * <p>패키지 안에 열어 둔다. 폴백 사다리(PrimaryChatCaller·FallbackChatService)가
     * "빈 응답인가"를 판정할 때 같은 규칙을 써야 하기 때문이다. 각자 꺼내면 한쪽은
     * {@code getResults().get(0)} 으로 꺼내다 빈 조각에서 터진다.
     */
    static String textOf(ChatClientResponse response) {
        ChatResponse chatResponse = (response == null) ? null : response.chatResponse();
        if (chatResponse == null) {
            return "";
        }
        List<Generation> results = chatResponse.getResults();
        if (results == null || results.isEmpty() || results.get(0).getOutput() == null) {
            return "";
        }
        String text = results.get(0).getOutput().getText();
        return (text == null) ? "" : text;
    }

    private static String text(Object value) {
        return (value == null) ? null : value.toString();
    }
}
