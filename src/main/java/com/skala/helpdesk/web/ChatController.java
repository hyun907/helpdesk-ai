package com.skala.helpdesk.web;

import com.skala.helpdesk.chat.AnswerDto;
import com.skala.helpdesk.chat.HelpDeskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.security.Principal;
import reactor.core.publisher.Flux;

/**
 * 상담 창구 — 받고 · 검증하고 · 서비스에 넘기고 · 응답 형태로 돌려준다.
 *
 * 이 파일에는 ChatClient 가 없다. 컨트롤러가 모델을 직접 부르기 시작하면
 * 프롬프트와 Advisor 설정이 화면 수만큼 흩어지고, 그 뒤로는 어느 경로에
 * 안전 필터가 걸려 있는지 아무도 확신하지 못한다.
 */
@RestController
@RequestMapping("/api/chat")
@Tag(name = "상담", description = "게임 고객지원 상담 · 동기 및 스트리밍")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final HelpDeskService helpDeskService;

    public ChatController(HelpDeskService helpDeskService) {
        this.helpDeskService = helpDeskService;
    }

    @PostMapping
    @Operation(summary = "상담 (동기)",
               description = "답변과 함께 근거 문서 목록, 실시간 조회(도구) 사용 여부를 돌려준다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "응답 생성"),
            @ApiResponse(responseCode = "400", description = "질문이 비었거나 길이 제한을 넘음")})
    public AnswerDto ask(
            Principal principal,
            @Valid @RequestBody AskRequest request) {
        return helpDeskService.ask(request.question(), principal.getName(), request.sessionId());
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "상담 (스트리밍)",
               description = "token 이벤트로 답변 조각을 흘리고, 마지막에 sources 이벤트로 근거 문서를 한 번 내보낸다.")
    public Flux<ServerSentEvent<Object>> stream(
            Principal principal,
            @Valid @RequestBody AskRequest request) {

        HelpDeskService.StreamSession session =
                helpDeskService.streamWithSources(request.question(), principal.getName(), request.sessionId());

        Flux<ServerSentEvent<Object>> tokens = session.tokens()
                .map(token -> ServerSentEvent.builder((Object) token).event("token").build());

        // defer 로 감싸야 앞의 토큰이 모두 끝난 뒤에 근거를 읽는다.
        // 지금 바로 lastSources() 를 부르면 아직 검색 결과가 채워지기 전이라 늘 빈 목록이 나간다.
        Flux<ServerSentEvent<Object>> sources = Flux.defer(() -> Flux.just(
                ServerSentEvent.builder((Object) session.lastSources()).event("sources").build()));

        return tokens.concatWith(sources);
    }

    // 근거 목록은 직렬화를 프레임워크에 맡긴다.
    // 직접 ObjectMapper 를 주입받으면 Jackson 버전에 코드가 묶인다 —
    // Boot 4 는 Jackson 3(tools.jackson)을 쓰므로 Jackson 2 의 ObjectMapper 빈은 존재하지 않는다.

    /**
     * 상담 요청.
     *
     * 질문 길이 상한은 사용성 제한이 아니라 비용 방어다. 상한이 없으면
     * 요청 한 번으로 컨텍스트 상한까지 밀어 넣어 토큰 비용을 임의로 태울 수 있다.
     */
    public record AskRequest(
            @NotBlank(message = "질문을 입력해 주세요.")
            @Size(max = 2000, message = "질문은 2000자를 넘을 수 없습니다.")
            @Schema(example = "어제 잃어버린 아이템 복구하고 싶어요. 캐릭터는 CH-1001 입니다.")
            String question,

            @NotBlank(message = "세션 ID 가 필요합니다.")
            @Schema(description = "대화를 이어 붙일 단위. 새 상담을 시작하려면 새 값을 보낸다.", example = "sess-001")
            String sessionId) {
    }
}
