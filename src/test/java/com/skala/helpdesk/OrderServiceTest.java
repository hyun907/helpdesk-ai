package com.skala.helpdesk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skala.helpdesk.repository.OrderRepository;
import com.skala.helpdesk.service.OrderService;
import com.skala.helpdesk.web.OrderNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * 슬라이스 테스트 — JPA 관련 빈만 로드된다(AI 자동구성 제외).
 * Boot 4.x 에서 @DataJpaTest 패키지가 org.springframework.boot.data.jpa.test.autoconfigure 로 이동했다.
 * Phase 0 의 핵심 검증: 남의 주문은 '없는 것'으로 처리된다.
 */
@DataJpaTest
@Import(OrderService.class)
@ActiveProfiles("local")
class OrderServiceTest {

    @Autowired OrderService service;
    @Autowired OrderRepository repository;

    @Test
    void 본인_주문은_조회된다() {
        assertThat(service.find("12345", "user1").item()).isEqualTo("무선 이어폰");
    }

    @Test
    void 남의_주문은_찾을_수_없다() {
        // 99999 는 user2 소유 — user1 에게는 '없는 주문'과 같은 응답이어야 한다
        assertThatThrownBy(() -> service.find("99999", "user1"))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void 없는_주문도_같은_예외다() {
        assertThatThrownBy(() -> service.find("00000", "user1"))
                .isInstanceOf(OrderNotFoundException.class);
    }
}
