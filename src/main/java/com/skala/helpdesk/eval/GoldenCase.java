package com.skala.helpdesk.eval;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 골든셋 한 문항 — 정답이 사람 머릿속이 아니라 파일에 적혀 있어야 한다.
 *
 * json 의 키는 짧게(q·must·src·note) 두고 자바 쪽은 읽기 쉬운 이름을 쓴다.
 * 문항을 손으로 계속 늘려 갈 파일이므로, 편집할 때 눈에 덜 걸리는 쪽을 json 에 맞췄다.
 *
 * <p>필드가 넷뿐인 이유:
 * <ul>
 *   <li>{@code question} — 실제로 사용자가 칠 법한 문장. 문서 제목을 그대로 옮기지 않는다.</li>
 *   <li>{@code must}     — 답변에 반드시 들어가야 할 조각. 문장 전체가 아니라 <b>문서 원문의 짧은 문자열</b>이다.
 *       답변 전체를 문자열로 단정하면 표현이 조금만 바뀌어도 깨져서, 곧 아무도 믿지 않는 테스트가 된다.</li>
 *   <li>{@code source}   — 근거 문서 파일명. {@code null} 이면 "문서에 답이 없어야 정상"인 문항이다.</li>
 *   <li>{@code note}     — 이 문항이 무엇을 잡으려고 있는지. 실패했을 때 사람이 읽을 유일한 단서다.</li>
 * </ul>
 */
public record GoldenCase(
        @JsonProperty("q") String question,
        @JsonProperty("must") List<String> must,
        @JsonProperty("src") String source,
        @JsonProperty("note") String note) {

    /** 근거 문서를 채점 대상으로 삼는 문항인가. src 가 비면 "지어내지 않는지"만 본다. */
    public boolean expectsSource() {
        return source != null && !source.isBlank();
    }

    /** 문서에 답이 없어야 정상인 문항. 지어내기 검출용이다. */
    public boolean outOfScope() {
        return !expectsSource();
    }
}
