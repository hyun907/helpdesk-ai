package com.skala.helpdesk;

import static org.assertj.core.api.Assertions.assertThat;

import com.skala.helpdesk.chat.HelpDeskService;
import com.skala.helpdesk.eval.GoldenCase;
import com.skala.helpdesk.eval.GoldenSet;
import com.skala.helpdesk.rag.IngestService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 골든셋 평가 — "고쳤더니 좋아졌다"를 느낌이 아니라 숫자로 말하기 위한 테스트.
 *
 * 클래스 이름이 {@code *EvalTest} 인 것은 규약이다. 실제 모델을 호출하므로
 * 일반 빌드에서는 제외되고 {@code -Peval} 을 줄 때만 돈다(build.gradle 의 test 태스크).
 * 평가를 매 커밋마다 돌리면 비용도 문제지만, 모델의 흔들림 때문에 빨간불이 일상이 되어
 * 아무도 테스트 결과를 보지 않게 된다.
 *
 * <p>실행 전제: pgvector 가 떠 있고, 문서가 인제스트되어 있고, OPENAI_API_KEY 가 있어야 한다.
 *
 * <p>채점 규칙은 둘뿐이다.
 * <ol>
 *   <li>{@code must} 키워드가 답변에 모두 들어 있는가</li>
 *   <li>{@code src} 가 있으면 그 문서가 출처에 잡혔는가</li>
 * </ol>
 * 답변 문장 전체를 문자열로 단정하지 않는다. 모델은 같은 뜻을 매번 다르게 쓰기 때문에,
 * 그런 단정은 품질이 아니라 표현의 변덕을 측정하게 된다.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GoldenSetEvalTest {

    private static final Logger log = LoggerFactory.getLogger(GoldenSetEvalTest.class);

    /**
     * 허용 실패 수. 만점을 기준으로 두면 한 문항의 흔들림에 빌드가 끌려다닌다.
     *
     * <p>통과 개수가 아니라 <b>실패 허용치</b>로 둔 이유가 있다. 통과 개수로 박아 두면
     * 문항을 늘릴 때마다 이 숫자를 같이 고쳐야 하고, 고치는 것을 잊으면 기준선이 상대적으로
     * 헐거워진 채 계속 초록불이 된다. 문항이 늘어도 허용 실패는 그대로여야 한다.
     */
    private static final int MAX_FAILURES = 2;

    /**
     * 평가용 고정 사용자. 도구가 소유자 검증에 쓰는 값이다.
     *
     * <p><b>시드 데이터가 있는 계정이어야 한다.</b> 예전에는 "eval-user" 라는 존재하지 않는 계정을
     * 썼는데, 그러면 도구가 전부 "찾을 수 없습니다"만 돌려주므로 도구 경로를 평가할 방법이 없었다.
     * 실제로 그 계정으로는 티켓이 한 건도 생기지 않았고, 골든셋 어느 문항도 도구를 태우지 않았다.
     *
     * <p>대신 지켜야 할 것이 생긴다 — <b>평가 문항은 조회만 하고 접수하지 않아야 한다.</b>
     * player1 은 실제 소유자라 복구 접수가 성공한다. 접수를 유발하는 문항을 넣으면 평가를 돌릴 때마다
     * 담당자 승인 큐에 쓰레기 티켓이 쌓인다.
     */
    private static final String EVAL_USER_ID = "player1";

    @Autowired
    private GoldenSet goldenSet;

    @Autowired
    private HelpDeskService helpDeskService;

    @Autowired
    private IngestService ingestService;

    /**
     * 색인이 비어 있는 채로 평가를 돌리면 전 문항이 "근거 못 찾음"으로 떨어진다.
     * 그 결과를 보고 프롬프트를 고치기 시작하면 하루가 날아간다. 먼저 막는다.
     *
     * <p>여기서 직접 인제스트하지 않는 이유: 문서별 docType·dept 매핑의 주인은
     * AdminController 다. 테스트가 매핑을 다시 적으면 두 곳이 조용히 갈라진다.
     */
    @BeforeAll
    void 색인이_채워져_있는지_먼저_확인한다() {
        boolean indexed = !ingestService.inspect("아이템 복구 신청 기한", 1).isEmpty();
        assertThat(indexed)
                .as("벡터 색인이 비어 있다. POST /api/admin/ingest 를 먼저 실행하고 평가를 돌려라")
                .isTrue();
    }

    @Test
    @DisplayName("골든셋에서 허용 실패(2문항)를 넘지 않는다")
    void 골든셋_기준선을_지킨다() {
        List<Scored> results = new ArrayList<>();

        for (GoldenCase testCase : goldenSet.cases()) {
            results.add(score(testCase));
        }

        long pass = results.stream().filter(Scored::passed).count();
        long retrievalMiss = results.stream().filter(r -> r.failure() == Failure.RETRIEVAL).count();
        long generationMiss = results.stream().filter(r -> r.failure() == Failure.GENERATION).count();
        long toolMiss = results.stream().filter(r -> r.failure() == Failure.TOOL).count();
        long forbiddenHit = results.stream().filter(r -> r.failure() == Failure.FORBIDDEN).count();
        long multiTurnFail = results.stream()
                .filter(r -> !r.passed() && r.testCase().isMultiTurn()).count();
        int baseline = results.size() - MAX_FAILURES;

        results.stream().filter(r -> !r.passed()).forEach(GoldenSetEvalTest::report);

        // 실패 종류마다 고치는 사람도, 고치는 방법도 다르다. 합쳐서 세면 어디를 손댈지 알 수 없다.
        //   근거 못 찾음  → 청킹·임계값·질의 재작성 (검색 문제)
        //   도구 오작동   → @Tool 설명·시스템 프롬프트의 규정/본인데이터 구분 (도구 선택 문제)
        //   찾고도 틀림   → 시스템 프롬프트·근거 주입 방식 (생성 문제)
        //   금지 문구     → 거절 규칙과 답변 규칙이 한 답변에 함께 발동 (프롬프트 충돌)
        //   멀티턴        → 메모리 Advisor 순서·윈도우 크기·대화 ID (맥락 문제)
        log.warn("""
                ── 골든셋 평가 결과 ──
                  전체        {}  (멀티턴 {})
                  통과        {}
                  근거 못 찾음  {}  (출처가 비었거나 기대한 문서가 아니다 → 검색을 손봐야 한다)
                  도구 오작동  {}  (부를 걸 안 부르거나, 안 부를 걸 불렀다 → 도구 설명을 손봐야 한다)
                  찾고도 틀림  {}  (출처는 맞는데 키워드가 빠졌다 → 프롬프트를 손봐야 한다)
                  금지 문구   {}  (거절과 정답을 함께 내놨다 → 거절 규칙의 발동 조건을 좁혀야 한다)
                  멀티턴 실패  {}  (앞 턴을 못 짚는다 → 메모리 배선을 손봐야 한다)
                  기준선      {}""",
                results.size(), goldenSet.countMultiTurn(), pass,
                retrievalMiss, toolMiss, generationMiss, forbiddenHit, multiTurnFail, baseline);

        assertThat(pass).isGreaterThanOrEqualTo(baseline);
    }

    private Scored score(GoldenCase testCase) {
        // 문항마다 세션을 새로 판다. 한 세션에 몰아넣으면 앞 문항의 답이 대화 이력으로 남아
        // 뒤 문항을 도와준다. 표현만 바꾼 짝 문항이 특히 그렇고, 그 통과는 가짜다.
        //
        // 맥락이 필요한 문항은 그 맥락을 '이 세션 안에서' 직접 쌓는다 — 문항 사이에 새는 것과
        // 문항 안에서 의도적으로 쌓는 것은 다르다. 앞의 것은 오염이고 뒤의 것은 검증 대상이다.
        String sessionId = UUID.randomUUID().toString();

        // 준비 턴은 채점하지 않는다. 답변이 무엇이든 상관없고, 대화 이력에 남는 것이 목적이다.
        for (String setup : testCase.setup()) {
            helpDeskService.ask(setup, EVAL_USER_ID, sessionId);
        }

        // AnswerDto·Source 의 패키지와 필드 구성은 다른 작업자 담당이다.
        // var 로 받아 두면 그쪽 구조가 바뀌어도 평가 코드는 따라 흔들리지 않는다.
        var answered = helpDeskService.ask(testCase.question(), EVAL_USER_ID, sessionId);

        String answer = answered.answer() == null ? "" : answered.answer();
        // Source 의 필드 이름을 모르므로 문자열로 펼쳐 파일명 포함 여부만 본다.
        // 파일명은 record 의 toString 에 그대로 드러난다.
        String sources = String.valueOf(answered.sources());

        boolean sourceOk = !testCase.expectsSource() || sources.contains(testCase.source());
        List<String> missing = testCase.must().stream()
                .filter(keyword -> !answer.contains(keyword))
                .toList();
        List<String> forbidden = testCase.mustNot().stream()
                .filter(answer::contains)
                .toList();
        boolean toolOk = !testCase.checksTool() || testCase.expectsTool() == answered.toolUsed();

        Failure failure;
        if (!sourceOk) {
            // 근거를 못 찾았으면 키워드가 맞았더라도 검색 실패로 센다.
            // 근거 없이 맞힌 답은 다음번에도 맞으리라는 보장이 없다.
            failure = Failure.RETRIEVAL;
        }
        else if (!toolOk) {
            // 도구를 안 불렀는데 답이 그럴듯하면 지어낸 것이고,
            // 규정 질문에 도구를 불렀으면 질문에 답한 것이 아니다. 둘 다 문장만 봐서는 안 보인다.
            failure = Failure.TOOL;
        }
        else if (!missing.isEmpty()) {
            failure = Failure.GENERATION;
        }
        else if (!forbidden.isEmpty()) {
            // must 를 다 만족해도 여기서 걸릴 수 있다 — 거절과 정답을 함께 내놓은 답변이 그렇다.
            failure = Failure.FORBIDDEN;
        }
        else {
            failure = Failure.NONE;
        }

        return new Scored(testCase, answer, sources, answered.toolUsed(), missing, forbidden, failure);
    }

    /** 실패 문항은 사람이 읽고 원인을 나눠야 한다. 질문·답변·출처를 모두 남긴다. */
    private static void report(Scored r) {
        log.warn("""
                ── 실패 [{}] ──
                  준비 턴    {}
                  질문      {}
                  기대 출처  {}
                  실제 출처  {}
                  누락 키워드 {}
                  금지 문구  {}
                  도구 사용  {}
                  검증 의도  {}
                  답변
                {}""",
                r.failure().label(),
                // 멀티턴 문항이 깨졌을 때 무엇이 앞에 있었는지 모르면 재현조차 못 한다
                r.testCase().isMultiTurn() ? r.testCase().setup() : "(없음 — 단일 턴)",
                r.testCase().question(),
                r.testCase().expectsSource() ? r.testCase().source() : "(없어야 정상)",
                r.sources(),
                r.missing().isEmpty() ? "(없음)" : r.missing(),
                r.forbidden().isEmpty() ? "(없음)" : r.forbidden(),
                r.testCase().checksTool()
                        ? "%s (기대 %s)".formatted(r.toolUsed(), r.testCase().expectsTool())
                        : String.valueOf(r.toolUsed()),
                r.testCase().note(),
                indent(r.answer()));
    }

    private static String indent(String text) {
        return text.lines().map(line -> "    " + line).reduce((a, b) -> a + "\n" + b).orElse("    (빈 답변)");
    }

    /** 실패의 종류. 이름을 붙여 두면 로그를 grep 해서 추이를 볼 수 있다. */
    private enum Failure {
        NONE("통과"),
        RETRIEVAL("근거 못 찾음"),
        TOOL("도구 오작동"),
        GENERATION("찾고도 틀림"),
        FORBIDDEN("금지 문구 포함");

        private final String label;

        Failure(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    private record Scored(GoldenCase testCase,
                          String answer,
                          String sources,
                          boolean toolUsed,
                          List<String> missing,
                          List<String> forbidden,
                          Failure failure) {

        boolean passed() {
            return failure == Failure.NONE;
        }
    }
}
