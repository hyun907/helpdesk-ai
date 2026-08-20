package com.skala.helpdesk.service;

import com.skala.helpdesk.domain.Ticket;
import com.skala.helpdesk.domain.TicketStatus;
import com.skala.helpdesk.repository.TicketRepository;
import com.skala.helpdesk.web.dto.TicketResponse;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 승인 게이트 — 되돌릴 수 없는 행동이 사람의 손을 거쳐 나가는 자리.
 *
 * 아이템 복구는 게임 안에 없던 재화를 새로 만들어 낸다. 한 번 지급되면 거래를 타고 흩어져
 * 회수가 사실상 불가능하고, 잘못된 지급이 쌓이면 서버 경제 자체가 망가진다.
 * 제재 해제도 마찬가지로 제재 이력의 효력을 없애는 일이라 되돌리기 어렵다.
 *
 * 그래서 경로를 둘로 쪼갰다.
 *   - 도구(@Tool)는 접수(PENDING)까지만 만든다. 모델이 최악으로 오작동해도 결과는 "대기 중인 신청 한 건"이다.
 *   - 상태를 APPROVED 로 넘기는 일은 이 클래스에서만 일어난다.
 *
 * ★ 이 클래스의 어떤 메서드에도 @Tool 을 붙이면 안 된다. ★
 * 붙이는 순간 모델이 도구 목록에서 이 메서드를 보게 되고, 프롬프트 한 줄로 승인을 끌어낼 수 있다.
 * 도구 목록에 없다는 것이 이 경로의 유일하고 확실한 차단선이다 — 시스템 프롬프트로 "승인하지 마라"라고
 * 타이르는 방식은 방어가 아니다. 모델에게 하지 말라고 부탁한 일은 언젠가 일어난다.
 *
 * 두 번째 차단선은 @PreAuthorize 다. 컨트롤러 인가가 잘못 열려도 여기서 다시 걸린다.
 */
@Service
@Transactional(readOnly = true)
public class TicketApprovalService {

    private final TicketRepository tickets;

    public TicketApprovalService(TicketRepository tickets) {
        this.tickets = tickets;
    }

    /** 승인 대기 목록 — 오래된 것부터. 기다린 순서대로 처리되어야 민원이 줄어든다. */
    @PreAuthorize("hasRole('ADMIN')")
    public List<TicketResponse> pending() {
        return tickets.findByStatusOrderByCreatedAtAsc(TicketStatus.PENDING)
                .stream().map(TicketResponse::from).toList();
    }

    /** 승인 — 여기서부터 게임 서버에 실제 영향이 간다. */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public TicketResponse approve(String no) {
        Ticket ticket = mustBePending(no);
        ticket.approve();
        return TicketResponse.from(ticket);
    }

    /** 반려 — 반려도 되돌리지 않는다. 재신청은 새 티켓으로 받는다. */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public TicketResponse reject(String no) {
        Ticket ticket = mustBePending(no);
        ticket.reject();
        return TicketResponse.from(ticket);
    }

    /**
     * 이미 처리된 티켓은 다시 건드리지 않는다.
     * 이 검사가 없으면 GM 두 명이 같은 티켓을 동시에 열었을 때, 또는 승인 버튼을 두 번 눌렀을 때
     * 아이템이 두 번 지급된다. 중복 지급은 사후에 알아내기도 어렵다.
     */
    private Ticket mustBePending(String no) {
        Ticket ticket = tickets.findById(no)
                .orElseThrow(() -> new TicketNotFoundException(no));
        if (ticket.getStatus() != TicketStatus.PENDING) {
            throw new TicketAlreadyResolvedException(no, ticket.getStatus());
        }
        return ticket;
    }

    /**
     * 없는 티켓.
     * 별도 타입으로 두어야 ApiExceptionHandler 에 404 매핑을 추가할 수 있다.
     * 매핑을 붙이기 전까지는 기존의 포괄 핸들러가 500 으로 내려보낸다 — 승인 컨트롤러를 만들 때 함께 붙인다.
     */
    public static class TicketNotFoundException extends IllegalStateException {
        public TicketNotFoundException(String no) {
            super("티켓을 찾을 수 없습니다: " + no);
        }
    }

    /** 이미 승인 또는 반려된 티켓. 조용히 성공시키지 않고 명확히 실패시킨다. */
    public static class TicketAlreadyResolvedException extends IllegalStateException {
        public TicketAlreadyResolvedException(String no, TicketStatus status) {
            super("이미 " + status.label() + " 상태인 티켓입니다: " + no);
        }
    }
}
