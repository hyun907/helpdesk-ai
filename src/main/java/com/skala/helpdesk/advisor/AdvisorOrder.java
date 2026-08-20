package com.skala.helpdesk.advisor;

import org.springframework.ai.chat.client.advisor.api.Advisor;

/**
 * Advisor 실행 순서를 한 곳에 모은다. order 가 작을수록 바깥이고, 응답은 역순으로 돌아 나온다.
 *
 * <p><b>왜 Integer.MIN_VALUE 대역인가.</b> Spring AI 는 대화 메모리와 도구 호출의 순서를
 * 상수로 예약해 두었다.
 * <pre>
 *   Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER = MIN_VALUE + 200
 *   ToolCallingAdvisor.DEFAULT_ORDER             = MIN_VALUE + 300
 * </pre>
 * 즉 <b>메모리는 도구 호출 루프보다 바깥</b>에 있어야 한다. 이 규약을 모르고 메모리를
 * 0~1000 같은 값으로 두면 메모리가 도구 루프 안쪽으로 들어간다. 그러면 루프가 도는 동안
 * 매 반복마다 이력이 저장되는데, JDBC 저장소는 tool_calls 메시지를 지원하지 않아 조용히 버린다.
 * 결과는 "도구 결과 메시지는 있는데 그것을 요청한 어시스턴트 메시지가 없는" 잘못된 대화이고,
 * 모델 공급자가 400 으로 거절한다. 증상은 도구를 쓰는 질문만 전부 실패하는 것이라
 * 원인을 순서에서 찾기 어렵다.
 *
 * <p>그래서 우리 Advisor 들도 같은 대역으로 옮겨, 프레임워크가 정한 자리와 함께 정렬한다.
 * 우리가 지켜야 하는 불변식은 둘이다.
 * <ul>
 *   <li>감사는 가장 바깥 — 차단된 요청도 기록되어야 한다</li>
 *   <li>차단은 메모리 저장보다 앞 — 막았어야 할 문장이 이력에 남으면 안 된다</li>
 * </ul>
 */
public final class AdvisorOrder {

    private AdvisorOrder() {
    }

    /** 감사 — 가장 바깥. 차단된 요청도 기록되어야 한다. */
    public static final int AUDIT = Integer.MIN_VALUE + 10;

    /** 계측 — 전체 구간을 감싸야 실제 지연이 잡힌다. */
    public static final int TOKEN_METER = Integer.MIN_VALUE + 20;

    /** 차단 — 메모리 저장보다 반드시 앞. */
    public static final int SENSITIVE_INPUT = Integer.MIN_VALUE + 100;

    /** 대화 메모리 — 프레임워크가 예약한 자리를 그대로 쓴다. 도구 루프보다 바깥이다. */
    public static final int CHAT_MEMORY = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER;

    /**
     * 근거 검색 — 메모리 뒤, 도구 루프 앞.
     * 도구 루프 안쪽에 두면 반복마다 다시 검색해 같은 근거를 중복으로 붙인다.
     *
     * <p><b>메모리 뒤라는 것이 여기서는 필수 조건이다.</b> 검색을 맡은
     * RetrievalAugmentationAdvisor 는 프롬프트에 실린 메시지 전체를 대화 이력으로 읽고,
     * 그것으로 후속 질문을 독립 질의로 다시 쓴 뒤 검색한다(CompressionQueryTransformer).
     * 메모리 Advisor 보다 <i>앞</i>에 두면 그 시점의 프롬프트에는 앞 대화가 아직 없어서
     * 재작성기가 압축할 것을 못 찾고, "그럼 그건 몇 번까지 할 수 있나요?" 는 그 문장 그대로
     * 검색어가 된다 — 예전 QuestionAnswerAdvisor 시절과 똑같이 검색이 통째로 빈다.
     * 순서를 바꿔도 예외는 나지 않고 출처만 조용히 사라지므로, 증상만 보고는 원인을 못 찾는다.
     *
     * <p>즉 <b>순서와 질의 재작성은 둘 다 있어야 하고, 하나만으로는 아무 소용이 없다.</b>
     * 조립은 {@code AiConfig.ragAdvisor(...)} 에 있다.
     */
    public static final int QUESTION_ANSWER = Integer.MIN_VALUE + 250;
}
