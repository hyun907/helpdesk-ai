package com.skala.helpdesk.tools;

import com.skala.helpdesk.chat.HelpDeskService;
import com.skala.helpdesk.domain.Sanction;
import com.skala.helpdesk.domain.Ticket;
import com.skala.helpdesk.domain.TicketType;
import com.skala.helpdesk.repository.GameCharacterRepository;
import com.skala.helpdesk.repository.SanctionRepository;
import com.skala.helpdesk.repository.TicketRepository;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;
import com.skala.helpdesk.config.HelpDeskProperties;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 접수 도구 — 여기서 만들 수 있는 최대치는 '승인대기' 상태의 신청서 한 장이다.
 *
 * 승인 메서드를 두지 않은 것이 설계다. 있으면 언젠가 모델이 부른다.
 * 아이템 지급과 제재 해제는 되돌릴 수 없는 행동이고, 되돌릴 수 없는 행동을
 * 프롬프트로 설득 가능한 주체에게 맡기지 않는다. APPROVED 로 넘기는 경로는
 * 사람이 누르는 화면에만 있다.
 *
 * 접수 전에 소유 확인을 먼저 한다. 검증 없이 저장하면 남의 캐릭터·남의 제재에 대한
 * 신청서가 티켓 큐에 쌓이고, GM 은 그 신청서를 정상 접수로 보고 승인하게 된다.
 * 즉 이 검증을 빠뜨리면 사고는 도구가 아니라 GM 의 손에서 완성된다.
 */
@Component
@Transactional(readOnly = true)
public class TicketTools {

    private static final Logger log = LoggerFactory.getLogger(TicketTools.class);

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 티켓 번호 형식. 자리수를 넘기면 정렬이 깨지므로 네 자리로 고정한다. */
    private static final String TICKET_NO_FORMAT = "T-%04d";

    /** 접수 응답에 반드시 붙는 문구. 모델이 '처리 완료'로 말을 바꾸지 못하게 결과 문장에 박아 둔다. */
    private static final String APPROVAL_NOTICE = "담당자 승인 후 처리됩니다.";

    /** Ticket.detail 컬럼은 1000자다. 넘겨서 저장하면 접수 자체가 예외로 실패한다. */
    private static final int MAX_DETAIL = 900;

    private static final String CHARACTER_NOT_FOUND =
            "해당 캐릭터를 찾을 수 없습니다. 본인 계정의 캐릭터로만 복구를 신청할 수 있습니다.";

    private static final String NEED_EXACT_DATE =
            "손실 날짜를 yyyy-MM-dd 형식으로 알려 주셔야 접수할 수 있습니다. 예: 2026-08-18";

    private static final String FUTURE_DATE =
            "손실 날짜가 미래로 되어 있습니다. 실제로 사라진 날짜를 다시 알려 주시기 바랍니다.";

    private static final String SANCTION_NOT_FOUND =
            "해당 제재 내역을 찾을 수 없습니다. 본인 계정에 부과된 제재만 이의신청할 수 있습니다.";

    private final TicketRepository tickets;
    private final GameCharacterRepository characters;
    private final SanctionRepository sanctions;
    private final HelpDeskProperties props;

    public TicketTools(TicketRepository tickets,
                       GameCharacterRepository characters,
                       SanctionRepository sanctions,
                       HelpDeskProperties props) {
        this.tickets = tickets;
        this.characters = characters;
        this.sanctions = sanctions;
        this.props = props;
    }

    @Tool(name = "submitItemRecovery",
          description = "이용자가 사라진 아이템의 복구를 요청할 때, 복구 신청서를 접수한다. "
                  + "캐릭터 ID·아이템 이름·손실 시점이 모두 확인된 다음에만 사용한다. 빠진 정보가 있으면 먼저 되묻는다. "
                  + "이 도구는 접수만 하며 아이템을 지급하지 않는다. 실제 복구는 담당자 승인 후에 이루어진다.")
    @Transactional
    public String submitItemRecovery(
            @ToolParam(description = "복구 대상 캐릭터 ID. 'CH-' 뒤에 네 자리 숫자가 붙는 형식이다. 예: CH-1001")
            String characterId,
            @ToolParam(description = "사라진 아이템 이름. 이용자가 말한 그대로 적는다. 예: 새벽의 검")
            String itemName,
            @ToolParam(description = "손실이 발생한 날짜. 반드시 yyyy-MM-dd 형식으로 적는다. 예: 2026-08-18. "
                    + "이용자가 '어제'처럼 말했다면 대화 앞부분의 [오늘 ...] 을 기준으로 계산해 실제 날짜로 바꾼다. "
                    + "날짜를 특정할 수 없으면 이 도구를 부르지 말고 이용자에게 먼저 되묻는다.")
            String occurredOn,
            ToolContext toolContext) {

        String ownerId = HelpDeskService.callerId(toolContext);
        HelpDeskService.markToolUsed(toolContext, "submitItemRecovery");

        // 저장보다 검증이 먼저다. 순서를 바꾸면 반려될 신청서가 이미 큐에 들어간 상태가 된다.
        if (characters.findByIdAndOwnerId(characterId, ownerId).isEmpty()) {
            log.info("복구 접수 거절 characterId={} — 없거나 다른 계정 소유", characterId);
            return CHARACTER_NOT_FOUND;
        }

        LocalDate lostOn;
        try {
            lostOn = LocalDate.parse(occurredOn.trim());
        } catch (DateTimeParseException | NullPointerException e) {
            // 모델이 날짜를 못 정하면 지어낸다. 형식을 못 박고, 안 맞으면 되묻게 한다.
            log.info("복구 접수 거절 — 날짜 파싱 실패 occurredOn={}", occurredOn);
            return NEED_EXACT_DATE;
        }

        LocalDate today = LocalDate.now();
        if (lostOn.isAfter(today)) {
            log.info("복구 접수 거절 — 미래 날짜 lostOn={}", lostOn);
            return FUTURE_DATE;
        }

        // 기한이 지난 신청은 접수하지 않는다. 접수해 두면 반려될 것이 담당자 검토 큐를 채운다.
        int windowDays = props.recovery().claimWindowDays();
        if (lostOn.isBefore(today.minusDays(windowDays))) {
            log.info("복구 접수 거절 — 기한 초과 lostOn={} window={}일", lostOn, windowDays);
            return ("아이템 복구는 손실일로부터 %d일 이내에만 신청할 수 있습니다. "
                    + "알려 주신 %s 는 기한이 지나 접수되지 않습니다.").formatted(windowDays, lostOn);
        }

        String detail = trim("아이템 '%s' / 손실일: %s".formatted(itemName, lostOn));
        Ticket ticket = tickets.save(new Ticket(nextTicketNo(), ownerId, characterId,
                TicketType.ITEM_RECOVERY, detail));

        log.info("복구 신청 접수 ticketNo={} characterId={}", ticket.getNo(), characterId);
        return "아이템 복구 신청을 접수했습니다. 신청 번호는 %s 이고 현재 상태는 %s 입니다. %s"
                .formatted(ticket.getNo(), ticket.getStatus().label(), APPROVAL_NOTICE);
    }

    @Tool(name = "submitSanctionAppeal",
          description = "이용자가 자신에게 부과된 제재에 이의를 제기할 때, 이의신청서를 접수한다. "
                  + "먼저 제재 이력을 조회해 제재 ID 를 확인한 뒤에 사용한다. "
                  + "이 도구는 접수만 하며 제재를 해제하지 않는다. 해제 여부는 담당자가 판단한다.")
    @Transactional
    public String submitSanctionAppeal(
            @ToolParam(description = "이의신청 대상 제재 ID. 'SC-' 뒤에 네 자리 숫자가 붙는 형식이다. 예: SC-2001")
            String sanctionId,
            @ToolParam(description = "이용자가 밝힌 이의 사유. 이용자가 말한 내용을 요약해서 적는다. 예: 계정 도용 피해로 인한 채팅 제한")
            String reason,
            ToolContext toolContext) {

        String ownerId = HelpDeskService.callerId(toolContext);
        HelpDeskService.markToolUsed(toolContext, "submitSanctionAppeal");

        Sanction sanction = sanctions.findByIdAndOwnerId(sanctionId, ownerId).orElse(null);
        if (sanction == null) {
            log.info("이의신청 접수 거절 sanctionId={} — 없거나 다른 계정 소유", sanctionId);
            return SANCTION_NOT_FOUND;
        }

        // 정책상 이의신청은 제재 1건당 1회다. 중복 접수를 막지 않으면 같은 건이 큐에 쌓인다.
        boolean already = tickets.findByOwnerIdOrderByCreatedAtDesc(ownerId).stream()
                .filter(t -> t.getType() == TicketType.SANCTION_APPEAL)
                .anyMatch(t -> t.getDetail() != null && t.getDetail().startsWith("제재 " + sanctionId + "("));
        if (already) {
            log.info("이의신청 접수 거절 sanctionId={} — 이미 접수된 건이 있다", sanctionId);
            return "해당 제재에 대한 이의신청은 이미 접수되어 있습니다. 이의신청은 제재 1건당 1회만 가능합니다.";
        }

        String detail = trim("제재 %s(%s) 이의신청 / 사유: %s"
                .formatted(sanction.getId(), sanction.getType().label(), reason));
        // 제재는 계정 단위라 대상 캐릭터가 없다. characterId 는 null 로 둔다.
        Ticket ticket = tickets.save(new Ticket(nextTicketNo(), ownerId, null,
                TicketType.SANCTION_APPEAL, detail));

        log.info("이의신청 접수 ticketNo={} sanctionId={}", ticket.getNo(), sanctionId);
        return "제재 이의신청을 접수했습니다. 신청 번호는 %s 이고 현재 상태는 %s 입니다. %s"
                .formatted(ticket.getNo(), ticket.getStatus().label(), APPROVAL_NOTICE);
    }

    @Tool(name = "lookupMyTickets",
          description = "이용자가 이전에 접수한 신청(아이템 복구·제재 이의신청)의 진행 상태를 확인할 때 사용한다. "
                  + "\"내 신청 어떻게 됐나요\", \"승인 됐나요\" 같은 문의에 답하기 전에 이 도구로 확인한다. "
                  + "문의한 본인 계정의 신청만 조회된다.")
    public String lookupMyTickets(ToolContext toolContext) {

        String ownerId = HelpDeskService.callerId(toolContext);
        HelpDeskService.markToolUsed(toolContext, "lookupMyTickets");

        List<Ticket> mine = tickets.findByOwnerIdOrderByCreatedAtDesc(ownerId);
        if (mine.isEmpty()) {
            return "접수된 신청 내역이 없습니다.";
        }

        String lines = mine.stream()
                .map(t -> "- %s · %s · %s · 접수 %s".formatted(
                        t.getNo(), t.getType().label(), t.getStatus().label(),
                        t.getCreatedAt() == null ? "기록 없음" : STAMP.format(t.getCreatedAt())))
                .collect(Collectors.joining("\n"));

        return "고객님의 신청 내역입니다.\n%s\n승인대기 상태의 신청은 %s".formatted(lines, APPROVAL_NOTICE);
    }

    /**
     * 다음 티켓 번호.
     *
     * count() 기준으로 시작해 비어 있는 번호를 찾는다. 반려·삭제로 생긴 구멍 때문에
     * count()+1 이 이미 쓰인 번호일 수 있고, 그대로 저장하면 기본키 충돌로 접수가 실패한다.
     * 동시 접수까지 막지는 못한다 — 운영 트래픽에서는 DB 시퀀스로 바꿔야 한다.
     */
    private String nextTicketNo() {
        long sequence = tickets.count() + 1;
        String no = TICKET_NO_FORMAT.formatted(sequence);
        while (tickets.existsById(no)) {
            sequence++;
            no = TICKET_NO_FORMAT.formatted(sequence);
        }
        return no;
    }

    /** 이용자가 넣은 긴 문장이 그대로 컬럼 길이를 넘기는 일을 막는다. */
    private String trim(String detail) {
        if (detail == null) {
            return "";
        }
        return detail.length() <= MAX_DETAIL ? detail : detail.substring(0, MAX_DETAIL) + "…";
    }
}
