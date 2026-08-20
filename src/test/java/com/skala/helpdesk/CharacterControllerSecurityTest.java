package com.skala.helpdesk;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skala.helpdesk.service.CharacterService;
import com.skala.helpdesk.web.CharacterController;
import com.skala.helpdesk.web.SecurityConfig;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 조회 주체가 요청 파라미터로 바뀌지 않는지 못 박는다.
 *
 * <p>이 테스트가 필요한 이유가 있다. 컨트롤러가 계정 ID 를 @RequestParam 으로 받던 시절,
 * player1 로 인증한 뒤 ownerId=player2 라고 적어 보내면 남의 캐릭터·인벤토리·제재 이력이
 * 전부 열렸다. 인증은 통과하므로 로그에도 이상이 남지 않는다.
 *
 * <p>지금은 Principal 에서만 가져오므로 파라미터를 넣을 자리가 없다. 하지만 누군가
 * "프런트에서 넘기기 편하니까" 파라미터를 되살릴 수 있다. 그때 이 테스트가 깨진다.
 */
@WebMvcTest(CharacterController.class)
@Import(SecurityConfig.class)
class CharacterControllerSecurityTest {

    @Autowired MockMvc mvc;

    @MockitoBean CharacterService characterService;

    @Test
    @DisplayName("ownerId 파라미터를 넣어도 인증 주체로만 조회한다")
    void 파라미터로_조회_주체를_바꿀_수_없다() throws Exception {
        given(characterService.findMine("player1")).willReturn(List.of());

        mvc.perform(get("/api/characters")
                        .param("ownerId", "player2")          // 남의 계정을 주입해 본다
                        .with(httpBasic("player1", "player1-pw")))
                .andExpect(status().isOk());

        // 파라미터가 아니라 인증 주체가 쓰였는지 확인한다
        verify(characterService).findMine(eq("player1"));
    }

    @Test
    @DisplayName("인벤토리도 인증 주체로만 조회한다")
    void 인벤토리도_주입되지_않는다() throws Exception {
        given(characterService.inventory("CH-9001", "player1")).willReturn(List.of());

        mvc.perform(get("/api/characters/CH-9001/inventory")
                        .param("ownerId", "player2")
                        .with(httpBasic("player1", "player1-pw")))
                .andExpect(status().isOk());

        verify(characterService).inventory(eq("CH-9001"), eq("player1"));
    }

    @Test
    @DisplayName("제재 이력도 인증 주체로만 조회한다")
    void 제재_이력도_주입되지_않는다() throws Exception {
        given(characterService.sanctionHistory("player1")).willReturn(List.of());

        mvc.perform(get("/api/characters/sanctions")
                        .param("ownerId", "player2")
                        .with(httpBasic("player1", "player1-pw")))
                .andExpect(status().isOk());

        verify(characterService).sanctionHistory(eq("player1"));
    }

    @Test
    @DisplayName("인증 없이는 조회할 수 없다")
    void 인증이_없으면_401() throws Exception {
        mvc.perform(get("/api/characters")).andExpect(status().isUnauthorized());
    }
}
