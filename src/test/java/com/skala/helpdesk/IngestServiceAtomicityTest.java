package com.skala.helpdesk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.skala.helpdesk.rag.IngestService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

/**
 * 인제스트가 <b>색인을 비우고 끝나지 않는지</b> 못 박는다.
 *
 * <p>이 테스트가 있는 이유는 실제로 두 번 당했기 때문이다. 예전 구현은 지우고 넣었다.
 * 삭제는 벡터 스토어 안에서 끝나지만 저장은 임베딩 API 를 부르므로, 키가 죽거나 429 가 나면
 * 지우기만 하고 끝난다. 그러면 그 문서에 대한 규정 답변이 전부 "확인되지 않습니다"가 되는데,
 * 검색도 정상이고 런타임 오류도 없어서 프롬프트와 임계값을 먼저 의심하게 된다.
 *
 * <p>순서는 눈으로 리뷰해서는 지켜지지 않는다. 두 줄을 바꿔 놓아도 컴파일되고, 평상시에는
 * 결과도 같다. 차이는 저장이 실패하는 날에만 드러난다.
 */
class IngestServiceAtomicityTest {

    private final VectorStore vectorStore = mock(VectorStore.class);
    private final IngestService ingestService = new IngestService(vectorStore);

    /** 조항 구분이 있는 최소 문서. 분할 경로를 타면서도 청크가 여러 개 나오도록 길이를 준다. */
    private static Resource policyDoc() {
        String body = """
                # 테스트 정책

                ## 1. 신청 기한
                신청은 손실이 발생한 시점으로부터 14일 이내에 접수해야 합니다.
                기한이 지난 건은 로그 보존 기간이 남아 있더라도 접수되지 않습니다.
                접수만으로는 아무것도 지급되지 않으며 담당자 승인을 거칩니다.

                ## 2. 신청 횟수
                한 계정에서 연 3회까지 신청할 수 있습니다.
                서버 장애로 인한 건은 이 횟수에 포함되지 않습니다.
                횟수를 넘긴 신청은 접수 단계에서 반려됩니다.
                """;
        return new ByteArrayResource(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "test-policy.md";
            }
        };
    }

    @Test
    @DisplayName("저장이 먼저이고 이전 세대 삭제는 그 다음이다")
    void 넣고_나서_지운다() {
        ingestService.ingest(policyDoc(), "policy", "CS");

        InOrder order = inOrder(vectorStore);
        order.verify(vectorStore).add(anyList());
        order.verify(vectorStore).delete(any(Filter.Expression.class));
    }

    @Test
    @DisplayName("저장이 실패하면 아무것도 지우지 않는다 — 이전 색인이 남는다")
    void 저장_실패가_색인을_비우지_않는다() {
        willThrow(new IllegalStateException("401 Incorrect API key provided"))
                .given(vectorStore).add(anyList());

        assertThatThrownBy(() -> ingestService.ingest(policyDoc(), "policy", "CS"))
                .isInstanceOf(IllegalStateException.class);

        // 여기가 이 테스트의 전부다. 삭제가 한 번이라도 불리면 문서가 색인에서 사라진다.
        verify(vectorStore, never()).delete(any(Filter.Expression.class));
    }

    /**
     * 세대 구분이 없으면 교체가 성립하지 않는다.
     * ingestId 가 빠지면 삭제 조건이 "같은 출처 전부"가 되어 방금 넣은 것까지 지운다.
     */
    @Test
    @DisplayName("모든 청크에 이번 실행의 ingestId 가 붙는다")
    void 세대_식별자를_붙인다() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);

        ingestService.ingest(policyDoc(), "policy", "CS");
        verify(vectorStore).add(captor.capture());

        List<Document> stored = captor.getValue();
        assertThat(stored).isNotEmpty();
        assertThat(stored).allSatisfy(d ->
                assertThat(d.getMetadata()).containsKey("ingestId"));
        assertThat(stored.stream().map(d -> d.getMetadata().get("ingestId")).distinct())
                .as("한 번의 인제스트는 한 세대다 — 청크마다 다르면 삭제 조건이 자기 자신을 지운다")
                .hasSize(1);
    }

    @Test
    @DisplayName("실행마다 다른 세대가 된다 — 같은 날 두 번 색인해도 겹치지 않는다")
    void 세대는_실행마다_새로_만든다() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);

        ingestService.ingest(policyDoc(), "policy", "CS");
        ingestService.ingest(policyDoc(), "policy", "CS");
        verify(vectorStore, org.mockito.Mockito.times(2)).add(captor.capture());

        Object first = captor.getAllValues().get(0).get(0).getMetadata().get("ingestId");
        Object second = captor.getAllValues().get(1).get(0).getMetadata().get("ingestId");
        // version(날짜)을 세대 키로 쓰면 여기서 같아진다 — 두 번째 실행이 첫 번째를 못 지운다
        assertThat(second).isNotEqualTo(first);
    }
}
