package com.skala.helpdesk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.skala.helpdesk.chat.HelpDeskService;
import com.skala.helpdesk.config.HelpDeskProperties;
import com.skala.helpdesk.domain.GameCharacter;
import com.skala.helpdesk.domain.Ticket;
import com.skala.helpdesk.repository.GameCharacterRepository;
import com.skala.helpdesk.repository.InventoryItemRepository;
import com.skala.helpdesk.repository.SanctionRepository;
import com.skala.helpdesk.repository.TicketRepository;
import com.skala.helpdesk.tools.TicketTools;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

/**
 * 도구 검증은 모델 없이 한다.
 *
 * <p>모델이 스스로 거절하는 것과 코드가 막는 것은 다르다. 앞의 것은 프롬프트와 근거에
 * 의존하므로 표현을 바꾸면 뚫릴 수 있다. 접수 자체가 성립하지 않는 조건은 코드가 막아야 한다.
 *
 * <p>실제로 손실 날짜 검증이 없던 시절, 3년 전 날짜로 복구 신청이 접수된 적이 있다.
 * 복구 정책은 14일 이내라 그 신청은 애초에 반려될 것이었는데, 담당자 검토 큐에는 들어갔다.
 */
class TicketToolsValidationTest {

    private static final int WINDOW_DAYS = 14;

    private TicketRepository tickets;
    private GameCharacterRepository characters;
    private TicketTools tools;

    @BeforeEach
    void setUp() {
        tickets = mock(TicketRepository.class);
        characters = mock(GameCharacterRepository.class);
        SanctionRepository sanctions = mock(SanctionRepository.class);

        // 캐릭터는 본인 소유로 존재한다고 둔다 — 여기서 보려는 것은 날짜 검증이다
        given(characters.findByIdAndOwnerId("CH-1001", "player1"))
                .willReturn(Optional.of(new GameCharacter(
                        "CH-1001", "player1", "달빛기사", "전사", 87, "아스가르드", LocalDateTime.now())));
        given(tickets.findAll()).willReturn(List.of());
        given(tickets.save(any(Ticket.class))).willAnswer(inv -> inv.getArgument(0));

        HelpDeskProperties props = new HelpDeskProperties(
                new HelpDeskProperties.Rag(5, 0.32),
                new HelpDeskProperties.Memory(20),
                new HelpDeskProperties.Fallback("gpt-4o"),
                new HelpDeskProperties.Recovery(WINDOW_DAYS),
                new HelpDeskProperties.Agent(8));

        tools = new TicketTools(tickets, characters, sanctions, props);
    }

    private ToolContext ctx() {
        Map<String, Object> map = new HashMap<>();
        map.put(HelpDeskService.USER_ID, "player1");
        map.put(HelpDeskService.TOOL_TRACE, java.util.concurrent.ConcurrentHashMap.newKeySet());
        return new ToolContext(map);
    }

    @Test
    @DisplayName("기한이 지난 손실일은 접수되지 않는다")
    void 기한_초과는_접수되지_않는다() {
        String past = LocalDate.now().minusDays(WINDOW_DAYS + 1).toString();

        String result = tools.submitItemRecovery("CH-1001", "달빛 대검", past, ctx());

        assertThat(result).contains("기한이 지나");
        verify(tickets, never()).save(any(Ticket.class));   // 큐에 들어가지 않는다
    }

    @Test
    @DisplayName("미래 날짜는 접수되지 않는다")
    void 미래_날짜는_접수되지_않는다() {
        String future = LocalDate.now().plusDays(1).toString();

        String result = tools.submitItemRecovery("CH-1001", "달빛 대검", future, ctx());

        assertThat(result).contains("미래");
        verify(tickets, never()).save(any(Ticket.class));
    }

    @Test
    @DisplayName("형식이 어긋난 날짜는 되묻는다")
    void 형식이_틀리면_되묻는다() {
        String result = tools.submitItemRecovery("CH-1001", "달빛 대검", "어제 저녁 9시쯤", ctx());

        assertThat(result).contains("yyyy-MM-dd");
        verify(tickets, never()).save(any(Ticket.class));
    }

    @Test
    @DisplayName("기한 안의 손실일은 접수된다")
    void 기한_안이면_접수된다() {
        String within = LocalDate.now().minusDays(1).toString();

        String result = tools.submitItemRecovery("CH-1001", "달빛 대검", within, ctx());

        assertThat(result).contains("접수").contains("승인");
        verify(tickets).save(any(Ticket.class));
    }

    @Test
    @DisplayName("남의 캐릭터로는 접수되지 않는다")
    void 남의_캐릭터로는_접수되지_않는다() {
        String within = LocalDate.now().minusDays(1).toString();

        String result = tools.submitItemRecovery("CH-9001", "폭풍의 인장", within, ctx());

        assertThat(result).doesNotContain("접수했습니다");
        verify(tickets, never()).save(any(Ticket.class));
    }
}
