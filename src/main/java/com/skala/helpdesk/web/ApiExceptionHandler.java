package com.skala.helpdesk.web;

import com.skala.helpdesk.web.dto.ErrorResponse;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 예외는 던지고, 응답으로 바꾸는 일은 여기 한 곳에서 한다.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(OrderNotFoundException e) {
        // 없는 주문과 남의 주문에 같은 응답을 준다 — 존재 여부를 알리지 않는다(존재 여부를 알리지 않는다)
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("주문을 찾을 수 없습니다.", null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleInvalid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest().body(new ErrorResponse(message, null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        log.error("[{}] 처리 중 오류", traceId, e);            // 상세는 로그에만
        return ResponseEntity.internalServerError()
                .body(new ErrorResponse("처리 중 문제가 발생했습니다.", traceId));
    }
}
