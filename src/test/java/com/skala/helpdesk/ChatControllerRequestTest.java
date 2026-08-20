package com.skala.helpdesk;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skala.helpdesk.chat.HelpDeskService;
import com.skala.helpdesk.web.ApiExceptionHandler;
import com.skala.helpdesk.web.ChatController;
import com.skala.helpdesk.web.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 잘못된 요청이 <b>잘못된 요청으로</b> 보이는지 확인한다.
 *
 * <p>이 테스트가 생긴 이유가 있다. 부하를 재던 중 500 이 여러 건 잡혀 애플리케이션 장애로 의심했는데,
 * 실제로는 측정 스크립트가 만든 깨진 JSON 이었다. 클라이언트 오류가 서버 오류로 보고되면
 * 두 가지가 동시에 망가진다 — 호출하는 쪽은 자기 요청이 잘못됐다는 것을 모른 채 재시도하고,
 * 운영하는 쪽은 5xx 지표를 보고 없는 장애를 쫓는다.
 *
 * <p>포괄 핸들러({@code @ExceptionHandler(Exception.class)})가 있는 한 이런 실수는 조용히 되살아난다.
 * 상태 코드를 테스트로 박아 두는 편이 낫다.
 */
@WebMvcTest(ChatController.class)
@Import({SecurityConfig.class, ApiExceptionHandler.class})
class ChatControllerRequestTest {

    @Autowired MockMvc mvc;

    /** 컨트롤러가 서비스까지 가지 않는 것도 확인 대상이다 — 본문을 못 읽었으면 모델을 부르면 안 된다. */
    @MockitoBean HelpDeskService helpDeskService;

    @Test
    @DisplayName("깨진 JSON 본문은 500 이 아니라 400 이다")
    void 깨진_본문은_클라이언트_오류다() throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":,\"sessionId\":\"s-1\"}")   // 값이 빠진 자리
                        .with(httpBasic("player1", "player1-pw")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("빈 질문은 400 이다")
    void 빈_질문은_거절한다() throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"\",\"sessionId\":\"s-1\"}")
                        .with(httpBasic("player1", "player1-pw")))
                .andExpect(status().isBadRequest());
    }

    /**
     * 길이 상한은 사용성 제한이 아니라 비용 방어다.
     * 상한이 없으면 요청 한 번으로 컨텍스트 상한까지 밀어 넣어 토큰 비용을 임의로 태울 수 있다.
     * 레드팀 8번(비용 공격)이 이 줄에서 막힌다.
     */
    @Test
    @DisplayName("2000자를 넘는 질문은 400 이다")
    void 초장문은_길이_제한에서_막힌다() throws Exception {
        String tooLong = "가".repeat(2001);
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"" + tooLong + "\",\"sessionId\":\"s-1\"}")
                        .with(httpBasic("player1", "player1-pw")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("인증 없이는 401 이다 — 본문이 깨졌더라도 인증이 먼저다")
    void 인증이_본문_파싱보다_앞이다() throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":,}"))
                .andExpect(status().isUnauthorized());
    }
}
