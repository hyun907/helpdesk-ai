package com.skala.helpdesk.rag;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * 질의 재작성을 <b>앞 턴이 있을 때만</b> 돌린다.
 *
 * <p><b>왜 감싸는가.</b> {@code CompressionQueryTransformer} 는 부를 때마다 모델을 한 번 더 부른다.
 * 상담 한 번에 모델 호출이 둘이 되고, 그 비용은 대화의 90%를 차지하는 첫 턴에서 전부 낭비다 —
 * 첫 턴에는 압축할 앞 대화 자체가 없어서, 모델은 질문을 거의 그대로 베껴 돌려준다.
 * 돈과 지연(체감상 1~2초)을 내고 아무것도 얻지 못하는 호출이다.
 *
 * <p>그래서 게이트를 하나 둔다: <b>이력에 어시스턴트 발화가 있는가.</b>
 * 이 조건을 고른 이유가 있다.
 * <ul>
 *   <li>{@code Query.history()} 는 이번 요청의 프롬프트 메시지 전체다 — 메모리 Advisor 가 끼워 넣은
 *       앞 대화 <i>그리고 이번 턴의 사용자 메시지</i>가 함께 들어 있다. 그래서 "이력이 비었는가"로
 *       판정하면 첫 턴도 이력이 있는 것으로 보인다. 어시스턴트 발화만이 "앞 턴이 실제로 있었다"를 뜻한다.</li>
 *   <li>휴리스틱이 아니라 사실이다. "그건·그거 같은 지시어가 있으면 재작성한다" 류의 규칙도 생각했지만,
 *       지시어 없이 맥락에 기대는 후속 질문("며칠이라고 하셨죠?")을 놓치고 그때는 출처가 조용히 빈다 —
 *       비용을 아끼려다 이 변경으로 고치려던 버그를 되살리는 셈이다.</li>
 * </ul>
 *
 * <p>남는 비용은 <b>멀티턴 2번째 턴부터의 추가 모델 호출</b>이고, 그건 깎지 않는다.
 * 그 호출이 없으면 후속 질문은 검색어가 "그럼 그건 몇 번까지 할 수 있나요?" 하나뿐이라
 * 아무 근거도 못 찾는다. 근거 없는 답은 맞아도 다음번을 보장하지 못한다.
 *
 * <p>재작성 호출이 실패하면 이 클래스는 막지 않는다 — 예외는 그대로 위로 간다.
 * 검색 품질 저하를 조용히 감수하는 것보다, 폴백 사다리(FallbackChatService)가 보조 모델로
 * 갈아타게 두는 편이 낫다. 여기서 삼키면 "왜 후속 질문만 출처가 비지"를 다시 처음부터 찾아야 한다.
 */
public class FollowUpQueryTransformer implements QueryTransformer {

    private static final Logger log = LoggerFactory.getLogger(FollowUpQueryTransformer.class);

    private final QueryTransformer delegate;

    public FollowUpQueryTransformer(QueryTransformer delegate) {
        Assert.notNull(delegate, "delegate cannot be null");
        this.delegate = delegate;
    }

    @Override
    public Query transform(Query query) {
        Assert.notNull(query, "query cannot be null");

        if (!hasPriorTurn(query.history())) {
            // 첫 턴 — 재작성할 맥락이 없다. 모델을 부르지 않고 원문을 그대로 검색어로 쓴다.
            return query;
        }

        Query rewritten = delegate.transform(query);
        // 검색이 빗나갔을 때 "무엇으로 검색했는가"를 못 보면 원인 추적이 불가능하다.
        // 재작성은 모델이 하는 일이라 매번 다르게 나온다 — 로그가 유일한 기록이다.
        log.debug("후속 질문 재작성 · 원문=[{}] → 검색어=[{}]", query.text(), rewritten.text());
        return rewritten;
    }

    /**
     * 앞 턴이 실제로 있었는지 판정한다.
     *
     * <p>어시스턴트 메시지의 본문이 비어 있으면 앞 턴으로 치지 않는다. 도구 호출만 하고
     * 본문이 없는 어시스턴트 메시지가 이력에 남을 수 있는데, 그것을 앞 대화로 착각하면
     * 압축 모델에 빈 이력을 주면서 호출 비용만 낸다.
     */
    private static boolean hasPriorTurn(List<Message> history) {
        return history != null && history.stream()
                .anyMatch(message -> message.getMessageType() == MessageType.ASSISTANT
                        && StringUtils.hasText(message.getText()));
    }
}
