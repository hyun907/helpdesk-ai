package com.skala.helpdesk.web;

import com.skala.helpdesk.service.OrderService;
import com.skala.helpdesk.web.dto.OrderResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 받고 · 검증하고 · 서비스에 넘기고 · 응답 형태로 돌려준다.
 * 업무 규칙을 넣지 않는다. 그리고 이 파일에는 ChatClient 가 없다(설계 원칙).
 */
@RestController
@RequestMapping("/api/orders")
@Tag(name = "주문", description = "주문 조회 — Phase 0 계층 확인용")
public class OrderController {

    private final OrderService orderService;      // 서비스만 안다

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "주문 단건 조회",
               description = "소유자 조건을 쿼리 안에서 함께 건다. 남의 주문은 '없는 것'으로 응답한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "없거나 남의 주문")})
    public OrderResponse find(
            @Parameter(description = "주문번호", example = "12345") @PathVariable String orderId,
            @Parameter(description = "조회 주체", example = "user1") @RequestParam String userId) {
        return orderService.find(orderId, userId);
    }

    @GetMapping
    @Operation(summary = "내 주문 목록", description = "최근 5건")
    public List<OrderResponse> findMine(
            @Parameter(description = "조회 주체", example = "user1") @RequestParam String userId) {
        return orderService.findMine(userId);
    }
}
