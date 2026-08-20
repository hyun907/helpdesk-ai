package com.skala.helpdesk.chat;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 상담 응답 — 답변과 함께 '무엇을 근거로 했는지'를 반드시 같이 내보낸다.
 *
 * 근거를 응답에 싣지 않으면 나중에 오답이 나왔을 때 원인이 검색인지 프롬프트인지 모델인지 가릴 수 없다.
 * toolUsed 도 같은 이유로 있다 — 실시간 조회가 실제로 일어났는지 여부를 눈으로 확인해야
 * 모델이 문서 내용을 실시간 정보인 척 지어낸 경우를 잡아낼 수 있다.
 */
public record AnswerDto(
        String answer,
        List<Source> sources,
        @Schema(description = "도구(실시간 조회) 호출이 실제로 있었는지", example = "true") boolean toolUsed) {

    /**
     * 근거 문서 한 건. 본문은 담지 않는다 —
     * 검색된 청크 원문을 그대로 내보내면 사내 규정 전문이 API 로 새 나간다.
     */
    public record Source(
            @Schema(example = "item-recovery-policy.md") String document,
            @Schema(example = "2026-08-20") String version) {
    }

    private static final String UNKNOWN_ANSWER =
            "확인되지 않습니다. 문의하신 내용을 뒷받침할 근거를 찾지 못했습니다. "
                    + "캐릭터 ID 나 발생 시점 같은 구체적인 정보를 알려 주시면 다시 확인해 드리겠습니다.";

    /**
     * 근거를 못 찾았을 때의 정해진 응답.
     *
     * 이 자리를 비워 두거나 모델 출력에 맡기면, 근거가 없을 때 모델이 그럴듯한 문장을 지어낸다.
     * 답을 못 찾은 상태는 코드가 명시적으로 표현해야 한다.
     */
    public static AnswerDto unknown() {
        return new AnswerDto(UNKNOWN_ANSWER, List.of(), false);
    }
}
