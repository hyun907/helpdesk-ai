package com.skala.helpdesk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skala.helpdesk.service.CharacterService;
import com.skala.helpdesk.web.CharacterNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * 슬라이스 테스트 — JPA 관련 빈만 로드된다(AI 자동구성 제외).
 * 권한 경계는 모델을 거치지 않고 여기서 직접 검증한다.
 */
@DataJpaTest
@Import(CharacterService.class)
@ActiveProfiles("local")
class CharacterServiceTest {

    @Autowired CharacterService service;

    @Test
    @DisplayName("본인 캐릭터는 조회된다")
    void 본인_캐릭터는_조회된다() {
        assertThat(service.find("CH-1001", "player1").nickname()).isEqualTo("달빛기사");
    }

    @Test
    @DisplayName("남의 캐릭터는 찾을 수 없다")
    void 남의_캐릭터는_차단된다() {
        // CH-9001 은 player2 소유 — player1 에게는 '없는 캐릭터'와 같은 응답이어야 한다
        assertThatThrownBy(() -> service.find("CH-9001", "player1"))
                .isInstanceOf(CharacterNotFoundException.class);
    }

    @Test
    @DisplayName("없는 캐릭터도 같은 예외다")
    void 없는_캐릭터도_같은_예외다() {
        // 존재 여부를 알리지 않는다 — 남의 것과 없는 것이 구분되면 정보가 새어 나간다
        assertThatThrownBy(() -> service.find("CH-0000", "player1"))
                .isInstanceOf(CharacterNotFoundException.class);
    }

    @Test
    @DisplayName("남의 캐릭터 인벤토리도 차단된다")
    void 남의_인벤토리는_차단된다() {
        assertThatThrownBy(() -> service.inventory("CH-9001", "player1"))
                .isInstanceOf(CharacterNotFoundException.class);
    }

    @Test
    @DisplayName("인벤토리는 소유자에게만 보인다")
    void 본인_인벤토리는_보인다() {
        assertThat(service.inventory("CH-1001", "player1"))
                .extracting("itemName")
                .contains("달빛 대검");
    }

    @Test
    @DisplayName("제재 이력은 계정 단위로 격리된다")
    void 제재_이력이_격리된다() {
        assertThat(service.sanctionHistory("player1"))
                .allSatisfy(s -> assertThat(s.reason()).doesNotContain("비정상 프로그램"));
        assertThat(service.sanctionHistory("player2")).hasSize(1);
    }
}
