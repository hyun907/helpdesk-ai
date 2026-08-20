package com.skala.helpdesk.eval;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 골든셋 한 문항 — 정답이 사람 머릿속이 아니라 파일에 적혀 있어야 한다.
 *
 * json 의 키는 짧게(q·must·src·note) 두고 자바 쪽은 읽기 쉬운 이름을 쓴다.
 * 문항을 손으로 계속 늘려 갈 파일이므로, 편집할 때 눈에 덜 걸리는 쪽을 json 에 맞췄다.
 *
 * <p>필드가 다섯뿐인 이유:
 * <ul>
 *   <li>{@code question} — 실제로 사용자가 칠 법한 문장. 문서 제목을 그대로 옮기지 않는다.</li>
 *   <li>{@code must}     — 답변에 반드시 들어가야 할 조각. 문장 전체가 아니라 <b>문서 원문의 짧은 문자열</b>이다.
 *       답변 전체를 문자열로 단정하면 표현이 조금만 바뀌어도 깨져서, 곧 아무도 믿지 않는 테스트가 된다.</li>
 *   <li>{@code source}   — 근거 문서 파일명. {@code null} 이면 "문서에 답이 없어야 정상"인 문항이다.</li>
 *   <li>{@code setup}    — {@code question} 앞에 <b>같은 세션에서</b> 먼저 던질 준비 턴. 채점하지 않는다.
 *       비어 있으면 단일 턴 문항이다.</li>
 *   <li>{@code note}     — 이 문항이 무엇을 잡으려고 있는지. 실패했을 때 사람이 읽을 유일한 단서다.</li>
 * </ul>
 *
 * <p><b>왜 setup 이 필요한가.</b> 단일 턴 문항만으로는 맥락 유지가 깨져도 평가가 초록불이다.
 * "그건", "아까 말한 그거" 같은 질문은 앞 턴이 있어야 성립하는데, 문항마다 새 세션을 파는 구조에서는
 * 그런 질문을 아예 쓸 수 없었다. 그 사각지대에서 실제로 회귀가 한 번 났다.
 */
public record GoldenCase(
        @JsonProperty("q") String question,
        @JsonProperty("must") List<String> must,
        @JsonProperty("mustNot") List<String> mustNot,
        @JsonProperty("src") String source,
        @JsonProperty("setup") List<String> setup,
        @JsonProperty("tool") Boolean tool,
        @JsonProperty("note") String note) {

    /** json 에 없으면 null 로 들어온다. 호출부마다 null 검사를 하지 않도록 여기서 없앤다. */
    public GoldenCase {
        setup = (setup == null) ? List.of() : List.copyOf(setup);
        mustNot = (mustNot == null) ? List.of() : List.copyOf(mustNot);
    }

    /** 근거 문서를 채점 대상으로 삼는 문항인가. src 가 비면 "지어내지 않는지"만 본다. */
    public boolean expectsSource() {
        return source != null && !source.isBlank();
    }

    /** 준비 턴이 있는 문항. 맥락 유지를 보는 문항이다. */
    public boolean isMultiTurn() {
        return !setup.isEmpty();
    }

    /**
     * 답변에 들어 있으면 실패인 문자열이 지정된 문항인가.
     *
     * <p><b>must 만으로는 못 잡는 실패가 있다.</b> "확인할 수 없습니다. 잃어버린 아이템은
     * 달빛 대검입니다" 같은 답변은 거절과 정답을 한 문단에 함께 내놓는다. must 에 "달빛 대검" 만
     * 걸어 두면 이 답변은 통과한다 — 이용자는 둘 중 무엇이 맞는지 알 수 없는데도.
     * 들어가야 할 문자열만 채점하고 들어가면 안 될 문자열을 채점하지 않으면 이런 답변이 초록불이 된다.
     */
    public boolean hasForbidden() {
        return !mustNot.isEmpty();
    }

    /**
     * 도구 호출 여부를 채점하는가. 값이 없으면 채점하지 않는다.
     *
     * <p>세 값을 구분한다 — {@code true} 는 반드시 불려야 하고, {@code false} 는 절대 불리면 안 되며,
     * 없으면 보지 않는다. {@code false} 가 필요한 이유는 규정 질문 때문이다. 시스템 프롬프트는
     * "규정을 묻는 질문에는 도구를 쓰지 않는다"고 못 박고 있는데, 모델이 이용자의 제재 이력을
     * 조회한 뒤 "이력이 없으니 해당 없습니다"라고 답하면 규정 질문에 답한 것이 아니다.
     * 답변 문장만 보면 그럴듯해서 must 로는 걸리지 않는다.
     */
    public boolean checksTool() {
        return tool != null;
    }

    public boolean expectsTool() {
        return Boolean.TRUE.equals(tool);
    }

    /**
     * 문서에 답이 없어야 정상인 문항. 지어내기 검출용이다.
     *
     * <p>멀티턴 문항과 도구 문항은 여기서 뺀다. 답이 앞 턴이나 실시간 데이터에 있어서 src 가
     * 비어 있을 뿐, "지어내면 안 되는 질문"이 아니다. 섞어 세면 지어내기 검출 문항이
     * 몇 개인지를 알 수 없게 된다.
     */
    public boolean outOfScope() {
        return !expectsSource() && !isMultiTurn() && !expectsTool();
    }
}
