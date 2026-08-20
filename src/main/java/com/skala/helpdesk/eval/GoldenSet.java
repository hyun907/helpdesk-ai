package com.skala.helpdesk.eval;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 골든셋 적재 — 품질 기준선을 코드가 아니라 파일에 둔다.
 *
 * 문항을 자바 리터럴로 박아 두면 문항을 늘릴 때마다 컴파일이 필요해지고,
 * 결국 아무도 늘리지 않는다. 리소스 파일로 두면 상담 담당자도 문항을 보탤 수 있다.
 *
 * <p>기동 시점에 읽고 검증한다. 골든셋이 깨진 것을 평가 테스트를 돌리는 순간에야
 * 알게 되면, 정작 확인하고 싶었던 모델 품질 대신 json 오타를 디버깅하게 된다.
 */
@Component
public class GoldenSet {

    private static final Logger log = LoggerFactory.getLogger(GoldenSet.class);

    /**
     * 파서는 여기서 직접 만든다.
     * 컨테이너의 ObjectMapper 는 웹 응답 규약(널 처리·명명 전략)에 맞춰 언제든 바뀔 수 있고,
     * 그 변경이 평가 세트 해석까지 흔드는 것은 원하지 않는다.
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final List<GoldenCase> cases;

    public GoldenSet(@Value("classpath:/eval/golden.json") Resource goldenFile) {
        this.cases = load(goldenFile);
        validate(this.cases, goldenFile);
        log.info("골든셋 적재 완료 location={} cases={} 범위밖문항={} 멀티턴문항={}",
                goldenFile.getFilename(), cases.size(), countOutOfScope(), countMultiTurn());
    }

    /** 채점 대상 전체. 순서는 파일에 적힌 순서 그대로 — 실패 로그를 파일과 대조하기 쉬워야 한다. */
    public List<GoldenCase> cases() {
        return cases;
    }

    public int size() {
        return cases.size();
    }

    /** 문서에 답이 없어야 정상인 문항 수. 이 값이 0 이면 지어내기를 아예 못 잡는 세트라는 뜻이다. */
    public long countOutOfScope() {
        return cases.stream().filter(GoldenCase::outOfScope).count();
    }

    /**
     * 준비 턴이 붙은 문항 수. 이 값이 0 이면 맥락 유지가 깨져도 전 문항 초록불인 세트다.
     * 단일 턴만 있는 세트는 "3턴 이상 맥락을 유지한다"는 요구사항을 한 번도 확인하지 않는다.
     */
    public long countMultiTurn() {
        return cases.stream().filter(GoldenCase::isMultiTurn).count();
    }

    private static List<GoldenCase> load(Resource goldenFile) {
        try (InputStream in = goldenFile.getInputStream()) {
            List<GoldenCase> loaded = MAPPER.readValue(in, new TypeReference<List<GoldenCase>>() {});
            return List.copyOf(loaded);
        }
        catch (IOException | RuntimeException e) {
            // 여기서 조용히 빈 목록을 돌려주면 평가가 "0문항 전부 통과"로 초록불이 된다. 가장 나쁜 결과다.
            throw new IllegalStateException("골든셋을 읽지 못했다: " + goldenFile, e);
        }
    }

    /**
     * 최소 검증 — 채점기가 조용히 통과시켜 버리는 형태를 막는다.
     * must 가 비어 있으면 어떤 답변이든 통과하고, 그런 문항은 있으나 마나가 아니라 해롭다.
     */
    private static void validate(List<GoldenCase> cases, Resource goldenFile) {
        if (cases.isEmpty()) {
            throw new IllegalStateException("골든셋이 비어 있다: " + goldenFile);
        }
        for (int i = 0; i < cases.size(); i++) {
            GoldenCase c = cases.get(i);
            String where = "golden.json[" + i + "]";
            if (c.question() == null || c.question().isBlank()) {
                throw new IllegalStateException(where + " 질문이 비어 있다");
            }
            if (c.must() == null || c.must().isEmpty()) {
                throw new IllegalStateException(where + " must 가 비어 있다 — 무엇이든 통과하는 문항이 된다");
            }
            if (c.must().stream().anyMatch(k -> k == null || k.isBlank())) {
                throw new IllegalStateException(where + " must 에 빈 키워드가 있다 — 항상 통과한다");
            }
            // 빈 준비 턴은 모델 호출만 한 번 더 하고 맥락은 쌓지 않는다. 조용히 넘어가면 원인을 못 찾는다.
            if (c.setup().stream().anyMatch(s -> s == null || s.isBlank())) {
                throw new IllegalStateException(where + " setup 에 빈 문장이 있다 — 맥락을 쌓지 못한다");
            }
            // 빈 금지어는 모든 답변에 들어 있는 것으로 판정되어 문항을 항상 실패시킨다. must 의 반대 사고다.
            if (c.mustNot().stream().anyMatch(k -> k == null || k.isBlank())) {
                throw new IllegalStateException(where + " mustNot 에 빈 키워드가 있다 — 항상 실패한다");
            }
            // 같은 문자열이 양쪽에 있으면 어떤 답변도 통과할 수 없다. 눈으로는 잘 안 보인다.
            String collision = c.must().stream().filter(c.mustNot()::contains).findFirst().orElse(null);
            if (collision != null) {
                throw new IllegalStateException(
                        where + " must 와 mustNot 에 같은 키워드가 있다 — 통과가 불가능하다: " + collision);
            }
        }
    }
}
