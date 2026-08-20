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

    /** 12문항 중 10문항. 만점을 기준으로 두면 한 문항의 흔들림에 빌드가 끌려다닌다. */
    private static final int BASELINE_PASS = 10;

    /** 평가용 고정 사용자. 도구가 소유자 검증에 쓰는 값이므로 실제 계정 형식을 따른다. */
    private static final String EVAL_USER_ID = "eval-user";

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
    @DisplayName("골든셋 12문항 중 10문항 이상 통과한다")
    void 골든셋_기준선을_지킨다() {
        List<Scored> results = new ArrayList<>();

        for (GoldenCase testCase : goldenSet.cases()) {
            results.add(score(testCase));
        }

        long pass = results.stream().filter(Scored::passed).count();
        long retrievalMiss = results.stream().filter(r -> r.failure() == Failure.RETRIEVAL).count();
        long generationMiss = results.stream().filter(r -> r.failure() == Failure.GENERATION).count();

        results.stream().filter(r -> !r.passed()).forEach(GoldenSetEvalTest::report);

        // 두 실패는 고치는 사람도, 고치는 방법도 다르다. 합쳐서 세면 어디를 손댈지 알 수 없다.
        //   근거 못 찾음  → 청킹·임계값·질의 재작성 (검색 문제)
        //   찾고도 틀림   → 시스템 프롬프트·근거 주입 방식 (생성 문제)
        log.warn("""
                ── 골든셋 평가 결과 ──
                  전체        {}
                  통과        {}
                  근거 못 찾음  {}  (출처가 비었거나 기대한 문서가 아니다 → 검색을 손봐야 한다)
                  찾고도 틀림  {}  (출처는 맞는데 키워드가 빠졌다 → 프롬프트를 손봐야 한다)
                  기준선      {}""",
                results.size(), pass, retrievalMiss, generationMiss, BASELINE_PASS);

        assertThat(pass).isGreaterThanOrEqualTo(BASELINE_PASS);
    }

    private Scored score(GoldenCase testCase) {
        // 문항마다 세션을 새로 판다. 한 세션에 몰아넣으면 앞 문항의 답이 대화 이력으로 남아
        // 뒤 문항을 도와준다. 표현만 바꾼 짝 문항이 특히 그렇고, 그 통과는 가짜다.
        String sessionId = UUID.randomUUID().toString();

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

        Failure failure;
        if (!sourceOk) {
            // 근거를 못 찾았으면 키워드가 맞았더라도 검색 실패로 센다.
            // 근거 없이 맞힌 답은 다음번에도 맞으리라는 보장이 없다.
            failure = Failure.RETRIEVAL;
        }
        else if (!missing.isEmpty()) {
            failure = Failure.GENERATION;
        }
        else {
            failure = Failure.NONE;
        }

        return new Scored(testCase, answer, sources, answered.toolUsed(), missing, failure);
    }

    /** 실패 문항은 사람이 읽고 원인을 나눠야 한다. 질문·답변·출처를 모두 남긴다. */
    private static void report(Scored r) {
        log.warn("""
                ── 실패 [{}] ──
                  질문      {}
                  기대 출처  {}
                  실제 출처  {}
                  누락 키워드 {}
                  도구 사용  {}
                  검증 의도  {}
                  답변
                {}""",
                r.failure().label(),
                r.testCase().question(),
                r.testCase().expectsSource() ? r.testCase().source() : "(없어야 정상)",
                r.sources(),
                r.missing().isEmpty() ? "(없음)" : r.missing(),
                r.toolUsed(),
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
        GENERATION("찾고도 틀림");

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
                          Failure failure) {

        boolean passed() {
            return failure == Failure.NONE;
        }
    }
}
