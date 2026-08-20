package com.skala.helpdesk;

import static org.assertj.core.api.Assertions.assertThat;

import com.skala.helpdesk.advisor.ToolAuditAspect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 마스킹만 떼어 내 검증한다 — 모델도 스프링 컨텍스트도 띄우지 않는다.
 *
 * 감사 로그의 마스킹은 "돌려 보고 확인하는" 종류의 코드가 아니다.
 * 한 번 새어 나간 로그는 회수할 수 없고, 새어 나갔다는 사실조차 한참 뒤에 알게 된다.
 * 그래서 정규식을 고칠 때마다 여기가 먼저 깨져야 한다.
 */
class ToolAuditAspectTest {

    @Test
    @DisplayName("주민등록번호는 하이픈이 있든 없든 원문으로 남지 않는다")
    void 주민등록번호가_마스킹된다() {
        String withHyphen = ToolAuditAspect.mask("본인확인 900101-1234567 확인 바랍니다");
        assertThat(withHyphen).doesNotContain("900101-1234567").doesNotContain("1234567");

        String withoutHyphen = ToolAuditAspect.mask("본인확인 9001011234567 확인 바랍니다");
        assertThat(withoutHyphen).doesNotContain("9001011234567");

        // 문장 자체는 남아야 한다 — 통째로 지우면 조사할 때 아무 단서도 남지 않는다
        assertThat(withHyphen).contains("본인확인").contains("확인 바랍니다");
    }

    @Test
    @DisplayName("카드번호는 뒷자리까지 전부 지워진다")
    void 카드번호가_마스킹된다() {
        String withHyphen = ToolAuditAspect.mask("결제카드 4111-1111-1111-1111 로 결제했습니다");
        assertThat(withHyphen).doesNotContain("4111-1111-1111-1111").doesNotContain("1111");

        // 하이픈 없는 16자리가 주민번호 패턴에 먼저 걸리면 뒤 3자리가 살아남는다 — 그 회귀를 막는다
        String withoutHyphen = ToolAuditAspect.mask("결제카드 4111111111111111 로 결제했습니다");
        assertThat(withoutHyphen).doesNotContain("4111111111111111").doesNotContain("111");
    }

    @Test
    @DisplayName("이메일 주소가 마스킹된다")
    void 이메일이_마스킹된다() {
        String masked = ToolAuditAspect.mask("답변은 player1@example.com 으로 주세요");
        assertThat(masked).doesNotContain("player1@example.com").doesNotContain("@example.com");
    }

    @Test
    @DisplayName("한 문장에 여러 종류가 섞여 있어도 모두 지워진다")
    void 여러_패턴이_한꺼번에_마스킹된다() {
        String masked = ToolAuditAspect.mask(
                "900101-1234567 / 4111-1111-1111-1111 / gm@helpdesk.co.kr");
        assertThat(masked)
                .doesNotContain("1234567")
                .doesNotContain("4111")
                .doesNotContain("gm@helpdesk.co.kr");
    }

    @Test
    @DisplayName("도구 인자 배열도 같은 규칙으로 지워진다")
    void 인자_배열이_마스킹된다() {
        String masked = ToolAuditAspect.maskArgs(
                new Object[]{"CH-1001", "주민번호 900101-1234567 입니다", 42});

        assertThat(masked).doesNotContain("900101-1234567");
        // 민감하지 않은 값은 그대로 보여야 한다 — 전부 가리면 감사 로그의 쓸모가 사라진다
        assertThat(masked).contains("CH-1001").contains("42");
    }

    @Test
    @DisplayName("긴 인자는 잘려서 로그를 뒤덮지 않는다")
    void 긴_인자는_잘린다() {
        String longText = "가".repeat(500);
        String masked = ToolAuditAspect.maskArgs(new Object[]{longText});

        assertThat(masked).contains("...").hasSizeLessThan(longText.length());
    }

    @Test
    @DisplayName("null 과 빈 인자에서 터지지 않는다")
    void 빈_입력에서_터지지_않는다() {
        // 감사 코드가 예외를 던지면 도구 호출 자체가 실패한다 — 감사는 흐름을 바꾸지 않아야 한다
        assertThat(ToolAuditAspect.mask(null)).isNull();
        assertThat(ToolAuditAspect.mask("")).isEmpty();
        assertThat(ToolAuditAspect.maskArgs(null)).isEqualTo("[]");
        assertThat(ToolAuditAspect.maskArgs(new Object[]{})).isEqualTo("[]");
        assertThat(ToolAuditAspect.maskArgs(new Object[]{null})).isEqualTo("[null]");
    }
}
