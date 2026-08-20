package com.skala.helpdesk.service;

import com.skala.helpdesk.domain.Order;
import com.skala.helpdesk.repository.OrderRepository;
import com.skala.helpdesk.web.OrderNotFoundException;
import com.skala.helpdesk.web.dto.OrderResponse;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 업무 흐름과 트랜잭션 경계. 클래스 기본값은 조회(readOnly), 쓰기에서만 재정의한다.
 * Phase 4 의 OrderTools 도 같은 소유자 검증 규칙을 쓴다.
 */
@Service
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orders;

    public OrderService(OrderRepository orders) {      // 생성자 주입 (생성자 주입)
        this.orders = orders;
    }

    public OrderResponse find(String orderId, String userId) {
        Order order = orders.findByIdAndOwnerId(orderId, userId)   // 권한은 쿼리 안에
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        return OrderResponse.from(order);                          // 엔티티 → DTO
    }

    public List<OrderResponse> findMine(String userId) {
        return orders.findTop5ByOwnerIdOrderByOrderedAtDesc(userId)
                .stream().map(OrderResponse::from).toList();
    }
}
