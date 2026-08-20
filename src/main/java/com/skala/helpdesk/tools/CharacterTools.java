package com.skala.helpdesk.tools;

import com.skala.helpdesk.chat.HelpDeskService;
import com.skala.helpdesk.domain.GameCharacter;
import com.skala.helpdesk.domain.InventoryItem;
import com.skala.helpdesk.domain.Sanction;
import com.skala.helpdesk.repository.GameCharacterRepository;
import com.skala.helpdesk.repository.InventoryItemRepository;
import com.skala.helpdesk.repository.SanctionRepository;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 조회 도구 — 문서에 없는 실시간 정보를 모델에 넘긴다.
 *
 * 이 클래스의 규칙은 하나다. 계정 ID 는 파라미터로 받지 않는다.
 * 도구 파라미터로 받으면 그 값은 모델이 채우는 값이 되고, 모델은 이용자가
 * "친구 계정 player9 좀 봐 줘"라고 하면 그대로 채워 넣는다. 인가가 프롬프트로 뚫린다.
 * 계정은 서버가 toolContext 에 심어 둔 값만 쓰고, 검증은 자바 if 문이 아니라
 * 저장소 쿼리 조건절(findByIdAndOwnerId)로 한다 — 조건을 빠뜨릴 여지를 없앤다.
 *
 * 못 찾았을 때 예외를 던지지 않는 이유도 중요하다. 예외는 모델에게 '일시적 오류'로 읽혀
 * 같은 도구를 계속 다시 부르게 만든다. 끝난 상태는 끝난 문장으로 알려 준다.
 */
@Component
@Transactional(readOnly = true)
public class CharacterTools {

    private static final Logger log = LoggerFactory.getLogger(CharacterTools.class);

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 없는 캐릭터와 남의 캐릭터에 같은 문장을 준다 — 존재 여부 자체를 알리지 않는다. */
    private static final String CHARACTER_NOT_FOUND =
            "해당 캐릭터를 찾을 수 없습니다. 본인 계정의 캐릭터만 조회할 수 있습니다.";

    /** 인벤토리 전량을 문장으로 풀면 토큰이 수천 단위로 늘고 비용이 캐릭터 보유량에 비례한다. */
    private static final int MAX_ITEMS = 20;

    /**
     * 목록도 같은 이유로 상한을 둔다. 부캐를 수십 개 굴리는 계정이 실제로 있고,
     * 그 계정의 "내 캐릭터 뭐 있어?" 한 마디가 도구 응답만으로 프롬프트를 채워
     * 정작 규정 문서 근거가 컨텍스트에서 밀려나는 일이 생긴다.
     * 잘라 내되 전체 건수는 반드시 붙인다 — 건수를 빼면 모델이 목록을 전부로 단정해
     * "고객님 캐릭터는 10개입니다"라고 틀린 수를 말한다.
     */
    private static final int MAX_CHARACTERS = 10;

    private final GameCharacterRepository characters;
    private final InventoryItemRepository items;
    private final SanctionRepository sanctions;

    public CharacterTools(GameCharacterRepository characters,
                          InventoryItemRepository items,
                          SanctionRepository sanctions) {
        this.characters = characters;
        this.items = items;
        this.sanctions = sanctions;
    }

    @Tool(name = "lookupMyCharacters",
          description = "문의한 이용자 계정에 속한 캐릭터 전체 목록(캐릭터 ID·닉네임·직업·레벨·서버)을 확인할 때 사용한다. "
                  + "\"내 캐릭터 뭐뭐 있어\", \"어떤 캐릭터 키우고 있죠\"처럼 대상 캐릭터가 특정되지 않은 문의가 여기에 해당한다. "
                  + "캐릭터 ID 를 모르는 상태에서 상담을 시작할 때 먼저 이 도구로 ID 를 확보한 뒤 "
                  + "lookupCharacter · lookupInventory · submitItemRecovery 로 넘어간다. "
                  + "이미 캐릭터 ID 를 알고 한 캐릭터의 상세 상태만 필요하다면 이 도구 대신 lookupCharacter 를 쓴다. "
                  + "계정 단위 조회이므로 입력값이 필요 없고, 본인 계정의 캐릭터만 조회된다.")
    public String lookupMyCharacters(ToolContext toolContext) {

        // 계정은 서버가 심어 둔 값만 쓴다. 파라미터로 열어 두면 모델이 이용자 말대로 채우고,
        // "친구 계정도 같이 보여 줘" 한 문장에 남의 캐릭터 목록이 통째로 나간다.
        String ownerId = HelpDeskService.callerId(toolContext);
        HelpDeskService.markToolUsed(toolContext, "lookupMyCharacters");

        // 소유자 조건은 쿼리 조건절이다. findAll() 로 꺼내 자바에서 거르는 방식은
        // 조건을 빠뜨린 그 순간부터 전체 계정 목록이 모델 입력으로 흘러간다.
        List<GameCharacter> mine = characters.findByOwnerIdOrderByLevelDesc(ownerId);
        if (mine.isEmpty()) {
            // 예외를 던지지 않는다. 예외는 모델에게 일시적 오류로 읽혀 같은 도구를 다시 부르게 만든다.
            return "고객님 계정에 생성된 캐릭터가 없습니다.";
        }

        String lines = mine.stream()
                .limit(MAX_CHARACTERS)
                .map(c -> "- %s %s · %s 서버 · %s · 레벨 %d".formatted(
                        c.getId(), c.getNickname(), c.getServer(), c.getJob(), c.getLevel()))
                .collect(Collectors.joining("\n"));

        String more = (mine.size() > MAX_CHARACTERS)
                ? "\n(레벨이 높은 %d건만 보여 드립니다. 전체 %d건)".formatted(MAX_CHARACTERS, mine.size())
                : "";

        return "고객님 계정의 캐릭터 목록입니다.\n%s%s".formatted(lines, more);
    }

    @Tool(name = "lookupCharacter",
          description = "이용자 본인의 캐릭터 상태(닉네임·직업·레벨·서버·최근 접속 시각)를 확인할 때 사용한다. "
                  + "규정 문서에는 없는 실시간 정보이므로 캐릭터에 관한 질문에는 반드시 이 도구로 확인한다. "
                  + "캐릭터 ID 가 이미 특정된 경우에만 쓴다. ID 를 모르면 lookupMyCharacters 로 목록을 먼저 확인하고, "
                  + "이용자에게 ID 를 되묻거나 임의로 지어내지 않는다. "
                  + "조회 범위는 문의한 본인 계정으로 한정되며, 다른 이용자의 캐릭터는 조회할 수 없다.")
    public String lookupCharacter(
            @ToolParam(description = "조회할 캐릭터 ID. 'CH-' 뒤에 네 자리 숫자가 붙는 형식이다. 예: CH-1001")
            String characterId,
            ToolContext toolContext) {

        String ownerId = HelpDeskService.callerId(toolContext);
        HelpDeskService.markToolUsed(toolContext, "lookupCharacter");

        // 소유자 조건을 쿼리에 함께 건다. 꺼낸 뒤 비교하는 코드는 언젠가 비교를 빠뜨린다.
        return characters.findByIdAndOwnerId(characterId, ownerId)
                .map(this::describe)
                .orElseGet(() -> {
                    log.info("캐릭터 조회 실패 characterId={} — 없거나 다른 계정 소유", characterId);
                    return CHARACTER_NOT_FOUND;
                });
    }

    @Tool(name = "lookupInventory",
          description = "특정 캐릭터가 지금 보유한 아이템 목록과 등급을 확인할 때 사용한다. "
                  + "아이템 복구 문의에서 '그 아이템이 정말 사라졌는지' 확인해야 할 때 먼저 이 도구를 쓴다. "
                  + "캐릭터 소유자 본인만 조회할 수 있다.")
    public String lookupInventory(
            @ToolParam(description = "인벤토리를 볼 캐릭터 ID. 'CH-' 뒤에 네 자리 숫자가 붙는 형식이다. 예: CH-1001")
            String characterId,
            ToolContext toolContext) {

        String ownerId = HelpDeskService.callerId(toolContext);
        HelpDeskService.markToolUsed(toolContext, "lookupInventory");

        // 인벤토리 테이블에는 소유자 컬럼이 없다. 캐릭터 소유를 먼저 확인해야 계정 경계가 선다.
        if (characters.findByIdAndOwnerId(characterId, ownerId).isEmpty()) {
            return CHARACTER_NOT_FOUND;
        }

        List<InventoryItem> found = items.findByCharacterIdOrderByGradeDesc(characterId);
        if (found.isEmpty()) {
            return "%s 캐릭터의 인벤토리가 비어 있습니다.".formatted(characterId);
        }

        String lines = found.stream()
                .limit(MAX_ITEMS)
                .map(item -> "- %s(%s) %d개, 획득 %s".formatted(
                        item.getItemName(), item.getGrade().label(), item.getQuantity(), stamp(item.getAcquiredAt())))
                .collect(Collectors.joining("\n"));

        String more = (found.size() > MAX_ITEMS)
                ? "\n(등급이 높은 %d건만 보여 드립니다. 전체 %d건)".formatted(MAX_ITEMS, found.size())
                : "";

        return "%s 캐릭터의 보유 아이템입니다.\n%s%s".formatted(characterId, lines, more);
    }

    @Tool(name = "lookupSanctionHistory",
          description = "문의한 이용자 계정에 부과된 제재 이력(종류·사유·기간·현재 적용 여부)을 확인할 때 사용한다. "
                  + "제재 사유 안내나 이의신청 상담을 시작하기 전에 먼저 이 도구로 실제 이력을 확인한다. "
                  + "제재는 캐릭터가 아니라 계정 단위이므로 캐릭터 ID 는 필요하지 않다.")
    public String lookupSanctionHistory(ToolContext toolContext) {

        String ownerId = HelpDeskService.callerId(toolContext);
        HelpDeskService.markToolUsed(toolContext, "lookupSanctionHistory");

        List<Sanction> history = sanctions.findByOwnerIdOrderByStartedAtDesc(ownerId);
        if (history.isEmpty()) {
            return "고객님 계정에 등록된 제재 이력이 없습니다.";
        }

        String lines = history.stream()
                .map(this::describe)
                .collect(Collectors.joining("\n"));
        return "고객님 계정의 제재 이력입니다.\n" + lines;
    }

    // ──────────────────────────────────────────────────────────────
    // 엔티티가 아니라 문장을 돌려준다.
    // 엔티티나 JSON 을 그대로 넘기면 ownerId 같은 내부 식별자까지 모델 입력에 섞이고,
    // 그 값은 다음 답변 문장에 그대로 튀어나올 수 있다.
    // ──────────────────────────────────────────────────────────────

    private String describe(GameCharacter c) {
        return "캐릭터 %s(%s)는 %s 서버의 %s 직업, 레벨 %d 입니다. 최근 접속은 %s 입니다."
                .formatted(c.getId(), c.getNickname(), c.getServer(), c.getJob(), c.getLevel(),
                        stamp(c.getLastPlayedAt()));
    }

    private String describe(Sanction s) {
        String period = (s.getEndsAt() == null)
                ? "%s 부터 (종료일 없음)".formatted(stamp(s.getStartedAt()))
                : "%s ~ %s".formatted(stamp(s.getStartedAt()), stamp(s.getEndsAt()));
        String state = s.isActive() ? "적용 중" : "종료됨";
        return "- [%s] %s · 사유: %s · 기간: %s · 현재 %s"
                .formatted(s.getId(), s.getType().label(), s.getReason(), period, state);
    }

    private String stamp(LocalDateTime time) {
        return (time == null) ? "기록 없음" : STAMP.format(time);
    }
}
