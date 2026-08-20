package com.skala.helpdesk.web;

import com.skala.helpdesk.service.TicketApprovalService;
import com.skala.helpdesk.web.dto.ErrorResponse;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 예외는 던지고, 응답으로 바꾸는 일은 여기 한 곳에서 한다.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(CharacterNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(CharacterNotFoundException e) {
        // 없는 캐릭터와 남의 캐릭터에 같은 응답을 준다 — 존재 여부를 알리지 않는다(존재 여부를 알리지 않는다)
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("캐릭터를 찾을 수 없습니다.", null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleInvalid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest().body(new ErrorResponse(message, null));
    }

    /**
     * 인가 실패는 403 이다.
     * 포괄 핸들러가 이 예외까지 잡으면 정상 차단이 500 으로 나가고,
     * 그러면 권한 통제가 동작한 것인지 서버가 고장난 것인지 구분할 수 없게 된다.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("이 작업을 수행할 권한이 없습니다.", null));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleUnauthenticated(AuthenticationException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("인증이 필요합니다.", null));
    }

    @ExceptionHandler(TicketApprovalService.TicketNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTicketNotFound(
            TicketApprovalService.TicketNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("해당 신청 건을 찾을 수 없습니다.", null));
    }

    /** 이미 승인·반려된 건을 다시 처리하려는 시도는 충돌이다. 재승인은 재화 중복 지급으로 이어진다. */
    @ExceptionHandler(TicketApprovalService.TicketAlreadyResolvedException.class)
    public ResponseEntity<ErrorResponse> handleTicketResolved(
            TicketApprovalService.TicketAlreadyResolvedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("이미 처리된 신청 건입니다.", null));
    }

    /**
     * 읽을 수 없는 요청 본문은 400 이다.
     *
     * <p>포괄 핸들러에 맡기면 <b>클라이언트가 보낸 깨진 JSON 이 500 으로 나간다.</b>
     * 그러면 호출하는 쪽은 자기 요청이 잘못됐다는 것을 알 수 없어 그대로 재시도하고,
     * 우리 쪽은 서버 오류 지표가 올라가 장애로 보인다. 양쪽 다 엉뚱한 곳을 보게 된다.
     * 실제로 부하 측정 중 스크립트가 만든 깨진 본문이 500 으로 집계돼 애플리케이션 장애로 오인된 적이 있다.
     *
     * <p>파싱 실패 사유는 응답에 싣지 않는다. 어느 위치에서 무엇을 기대했는지가 그대로 나가면
     * 요청 스키마를 탐색하는 단서가 된다. 상세는 로그에만 남긴다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.warn("요청 본문을 읽지 못했다 — {}", e.getMostSpecificCause().toString());
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("요청 본문을 읽을 수 없습니다. JSON 형식을 확인해 주세요.", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        log.error("[{}] 처리 중 오류", traceId, e);            // 상세는 로그에만
        return ResponseEntity.internalServerError()
                .body(new ErrorResponse("처리 중 문제가 발생했습니다.", traceId));
    }
}
