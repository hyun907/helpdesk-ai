package com.skala.helpdesk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.skala.helpdesk.chat.HelpDeskService;
import com.skala.helpdesk.domain.GameCharacter;
import com.skala.helpdesk.repository.GameCharacterRepository;
import com.skala.helpdesk.repository.InventoryItemRepository;
import com.skala.helpdesk.repository.SanctionRepository;
import com.skala.helpdesk.tools.CharacterTools;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

/**
 * 목록 도구는 모델 없이 검증한다.
 *
 * <p>여기서 확인하려는 것은 "모델이 남의 목록을 요구했을 때 거절하더라"가 아니라,
 * <b>애초에 남의 목록을 만들어 낼 경로가 코드에 없다</b>는 사실이다. 계정은 toolContext 에서만
 * 오고 조회는 소유자 조건절로만 나가므로, 조회 인자는 프롬프트로 바꿀 수 없다.
 *
 * <p>상한 검증을 같이 두는 이유도 같다. 부캐를 수십 개 굴리는 계정 하나가
 * 도구 응답만으로 컨텍스트를 채우면 정작 규정 문서 근거가 밀려난다.
 * 그 사고는 예외 없이 조용히 일어나고, 증상은 "왜 갑자기 답이 부실하지"뿐이다.
 */
class CharacterToolsListTest {

    private static final int MAX_CHARACTERS = 10;

    private GameCharacterRepository characters;
    private CharacterTools tools;
    private Set<String> toolTrace;

    @BeforeEach
    void setUp() {
        characters = mock(GameCharacterRepository.class);
        InventoryItemRepository items = mock(InventoryItemRepository.class);
        SanctionRepository sanctions = mock(SanctionRepository.class);
        tools = new CharacterTools(characters, items, sanctions);
        toolTrace = ConcurrentHashMap.newKeySet();
    }

    private ToolContext ctx(String userId) {
        Map<String, Object> map = new HashMap<>();
        map.put(HelpDeskService.USER_ID, userId);
        map.put(HelpDeskService.TOOL_TRACE, toolTrace);
        return new ToolContext(map);
    }

    private GameCharacter character(String id, String ownerId, String nickname, int level) {
        return new GameCharacter(id, ownerId, nickname, "전사", level, "아스가르드", LocalDateTime.now());
    }

    @Test
    @DisplayName("본인 계정의 캐릭터만 목록에 나온다")
    void 본인_캐릭터만_나온다() {
        given(characters.findByOwnerIdOrderByLevelDesc("player1")).willReturn(List.of(
                character("CH-1001", "player1", "달빛기사", 87),
                character("CH-1002", "player1", "은하수", 42)));
        given(characters.findByOwnerIdOrderByLevelDesc("player2")).willReturn(List.of(
                character("CH-9001", "player2", "폭풍술사", 60)));

        String result = tools.lookupMyCharacters(ctx("player1"));

        assertThat(result).contains("CH-1001", "달빛기사", "CH-1002", "은하수");
        assertThat(result).doesNotContain("CH-9001", "폭풍술사");
        // 소유자 조건은 쿼리에 걸려야 한다 — 전체를 꺼내 자바에서 거르는 경로가 없어야 한다
        verify(characters).findByOwnerIdOrderByLevelDesc("player1");
        verify(characters, never()).findAll();
        // 계정 ID 같은 내부 식별자가 모델 입력에 섞이면 다음 답변 문장에 그대로 튀어나온다
        assertThat(result).doesNotContain("player1");
        assertThat(toolTrace).contains("lookupMyCharacters");
    }

    @Test
    @DisplayName("캐릭터가 없으면 예외가 아니라 안내 문장을 준다")
    void 캐릭터가_없으면_안내한다() {
        given(characters.findByOwnerIdOrderByLevelDesc("player3")).willReturn(List.of());

        String result = tools.lookupMyCharacters(ctx("player3"));

        // 예외를 던지면 모델은 일시적 오류로 읽고 같은 도구를 계속 다시 부른다
        assertThat(result).contains("없습니다");
    }

    @Test
    @DisplayName("보유 수가 상한을 넘으면 잘라 내고 전체 건수를 함께 알린다")
    void 상한을_넘으면_잘린다() {
        List<GameCharacter> many = IntStream.rangeClosed(1, 25)
                .mapToObj(i -> character("CH-2%03d".formatted(i), "player1", "부캐" + i, 100 - i))
                .toList();
        given(characters.findByOwnerIdOrderByLevelDesc("player1")).willReturn(many);

        String result = tools.lookupMyCharacters(ctx("player1"));

        long listed = result.lines().filter(line -> line.startsWith("- ")).count();
        assertThat(listed).isEqualTo(MAX_CHARACTERS);
        // 전체 건수를 빼면 모델이 잘린 목록을 전부로 단정해 캐릭터 수를 틀리게 말한다
        assertThat(result).contains("전체 25건");
        assertThat(result).contains("CH-2001").doesNotContain("CH-2025");
    }

    @Test
    @DisplayName("계정이 심어지지 않은 호출은 실행되지 않는다")
    void 계정이_없으면_실행되지_않는다() {
        // toolContext 에 계정이 없다는 것은 계정 경계 없이 도구가 돌 뻔했다는 뜻이다.
        // 이 경우만은 문장으로 넘기지 않는다 — 서버 조립 실수는 조용히 성공하면 안 된다.
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> tools.lookupMyCharacters(new ToolContext(Map.of())))
                .isInstanceOf(IllegalStateException.class);
        verify(characters, never()).findByOwnerIdOrderByLevelDesc(org.mockito.ArgumentMatchers.anyString());
    }
}
