package com.skala.helpdesk;

import static org.assertj.core.api.Assertions.assertThat;

import com.skala.helpdesk.advisor.AuditAdvisor;
import com.skala.helpdesk.advisor.TokenMeterAdvisor;
import com.skala.helpdesk.config.AiConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Advisor 순서를 코드로 못 박는다.
 *
 * 순서가 틀린 Advisor 는 조용히 실패한다. 예외도 나지 않고 로그도 남지 않으므로,
 * 사람이 눈으로 지키는 대신 테스트가 지키게 한다.
 */
class AdvisorOrderTest {

    private final AuditAdvisor audit = new AuditAdvisor();
    private final TokenMeterAdvisor meter = new TokenMeterAdvisor(new SimpleMeterRegistry());

    @Test
    @DisplayName("차단은 메모리 저장보다 앞에 있다")
    void 차단이_메모리보다_앞이다() {
        // 안전 필터가 메모리 뒤에 있으면, 차단했어야 할 문장이 이미 이력에 저장된 뒤다
        assertThat(AiConfig.SAFE_GUARD_ORDER).isLessThan(AiConfig.CHAT_MEMORY_ORDER);
    }

    @Test
    @DisplayName("감사는 체인의 가장 바깥에 있다")
    void 감사가_가장_바깥이다() {
        // 차단된 요청도 기록되려면 감사가 차단보다 바깥이어야 한다
        assertThat(audit.getOrder())
                .isLessThan(AiConfig.SAFE_GUARD_ORDER)
                .isLessThan(meter.getOrder());
    }

    @Test
    @DisplayName("계측은 검색·메모리 구간을 모두 감싼다")
    void 계측이_검색보다_바깥이다() {
        // 안쪽에 두면 검색·메모리 주입에 걸린 시간이 측정에서 빠진다
        assertThat(meter.getOrder())
                .isLessThan(AiConfig.CHAT_MEMORY_ORDER)
                .isLessThan(AiConfig.QUESTION_ANSWER_ORDER);
    }

    @Test
    @DisplayName("전체 순서: 감사 → 계측 → 차단 → 기억 → 근거")
    void 전체_순서가_유지된다() {
        assertThat(new int[]{
                audit.getOrder(), meter.getOrder(), AiConfig.SAFE_GUARD_ORDER,
                AiConfig.CHAT_MEMORY_ORDER, AiConfig.QUESTION_ANSWER_ORDER
        }).isSorted();
    }

    @Test
    @DisplayName("감사·계측은 스트리밍 경로에서도 동작한다")
    void 스트리밍에서도_누락되지_않는다() {
        // CallAdvisor 만 구현한 Advisor 는 스트리밍에서 조용히 건너뛰어진다
        assertThat(audit).isInstanceOf(org.springframework.ai.chat.client.advisor.api.StreamAdvisor.class);
        assertThat(meter).isInstanceOf(org.springframework.ai.chat.client.advisor.api.StreamAdvisor.class);
    }
}
