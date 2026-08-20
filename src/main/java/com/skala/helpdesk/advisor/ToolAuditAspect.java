package com.skala.helpdesk.advisor;

import com.skala.helpdesk.config.HelpDeskProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.model.ToolContext;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 도구 호출 감사 — 모델이 실제로 무엇을 했는지 남긴다.
 *
 * Advisor 는 대화 한 턴을 감싸지만, 그 안에서 도구가 몇 번 어떤 인자로 불렸는지는 보지 못한다.
 * 사고가 났을 때 답을 해야 하는 질문은 "모델이 뭐라고 답했나"가 아니라
 * "누구의 요청으로 어떤 도구가 어떤 인자로 실행됐나"다. 그 기록이 여기서 생긴다.
 *
 * 주의: 이 Aspect 는 스프링 빈으로 등록된 도구 객체에만 걸린다.
 * 도구를 new 로 만들어 ChatClient 에 넘기면 프록시를 거치지 않아 감사도 계측도 조용히 사라진다.
 */
@Aspect
@Component
public class ToolAuditAspect {

    private static final Logger log = LoggerFactory.getLogger(ToolAuditAspect.class);

    // ── 마스킹 패턴 ──────────────────────────────────────────────
    // 로그는 보존 기간이 길고(수개월~수년) 접근 범위가 넓다(개발·운영·분석·외부 수집기).
    // 요청 본문에서는 잠깐 스쳐 지나갈 값이 로그에 들어가는 순간 사실상 영구 저장된다.
    // 그래서 "필요하면 나중에 지운다"가 아니라 "애초에 남기지 않는다"로 간다.
    // 카드번호를 먼저 지운다 — 하이픈 없는 16자리는 주민번호 패턴(13자리)에 부분적으로 걸려
    // 뒤 3자리가 마스킹되지 않은 채 남는다.
    private static final Pattern CARD_NUMBER = Pattern.compile("\\d{4}-?\\d{4}-?\\d{4}-?\\d{4}");
    private static final Pattern RESIDENT_NUMBER = Pattern.compile("\\d{6}-?\\d{7}");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /** 인자 하나가 로그를 뒤덮지 않도록 자른다. 문의 본문은 수천 자가 들어올 수 있다. */
    private static final int MAX_ARG_LENGTH = 200;

    /** 한 요청 안에서 소비한 도구 호출 수를 세는 자리. ToolContext 를 통해 요청 단위로 격리된다. */
    public static final String CALL_BUDGET = "toolCallBudget";

    private static final String BUDGET_EXCEEDED =
            "요청을 완료하지 못했습니다. 조건을 좁혀 다시 요청해 주시기 바랍니다.";

    private final MeterRegistry registry;
    private final HelpDeskProperties props;

    public ToolAuditAspect(MeterRegistry registry, HelpDeskProperties props) {
        this.props = props;
        this.registry = registry;
    }

    @Around("@annotation(org.springframework.ai.tool.annotation.Tool)")
    public Object auditToolCall(ProceedingJoinPoint joinPoint) throws Throwable {
        String traceId = currentTraceId();
        String user = currentUser();
        String toolName = toolNameOf(joinPoint);
        String args = maskArgs(joinPoint.getArgs());

        // 상한을 넘으면 실행하지 않고 끊는다.
        // 에이전트는 스스로 멈추지 않는다. 결과가 애매하면 같은 도구를 계속 부르고,
        // 그 사이 토큰과 지연은 사용자가 아니라 우리가 부담한다.
        // 감사 지점에서 세는 이유는 여기가 모든 도구가 반드시 지나는 유일한 길목이기 때문이다.
        // 도구마다 세면 새 도구를 추가할 때 빠뜨린다.
        if (exceededBudget(joinPoint) && returnsString(joinPoint)) {
            log.warn("[{}] 도구 호출 상한 초과 · user={} · tool={} · limit={}",
                    traceId, user, toolName, props.agent().maxToolCalls());
            registry.counter("ai.tool.calls", "tool", toolName, "result", "budget_exceeded").increment();
            return BUDGET_EXCEEDED;
        }

        long started = System.nanoTime();
        try {
            Object result = joinPoint.proceed();
            long elapsed = elapsedMillis(started);
            log.info("[{}] tool 성공 · user={} · tool={} · args={} · {}ms", traceId, user, toolName, args, elapsed);
            registry.counter("ai.tool.calls", "tool", toolName, "result", "ok").increment();
            return result;
        } catch (Throwable e) {
            long elapsed = elapsedMillis(started);
            // 예외 메시지에도 사용자 입력이 섞여 들어온다. 그대로 찍지 않는다.
            log.warn("[{}] tool 실패 · user={} · tool={} · args={} · {}ms · {}",
                    traceId, user, toolName, args, elapsed, mask(e.toString()));
            registry.counter("ai.tool.calls", "tool", toolName, "result", "fail").increment();
            // 감사는 흐름을 바꾸지 않는다 — 삼켜 버리면 모델은 실패를 성공으로 읽고 이어서 이야기를 지어낸다.
            throw e;
        }
    }

    /**
     * 인자 배열을 로그 한 조각으로 만든다.
     * 값의 형태(길이·개수)는 남기되 내용은 지운다 — 조사할 때 필요한 건 "무엇이 왔나"의 윤곽이다.
     */
    /** 호출 인자에서 ToolContext 를 찾아 예산을 하나 소비한다. 넘었으면 true. */
    private boolean exceededBudget(ProceedingJoinPoint joinPoint) {
        for (Object arg : joinPoint.getArgs()) {
            if (arg instanceof ToolContext context) {
                Object holder = context.getContext().get(CALL_BUDGET);
                if (holder instanceof AtomicInteger counter) {
                    return counter.incrementAndGet() > props.agent().maxToolCalls();
                }
                return false;   // 예산이 안 실려 왔다 — 테스트 등 직접 호출 경로다
            }
        }
        return false;
    }

    /** 상한 문구를 돌려주려면 반환 타입이 String 이어야 한다. 아니면 그냥 진행한다. */
    private boolean returnsString(ProceedingJoinPoint joinPoint) {
        return joinPoint.getSignature() instanceof MethodSignature signature
                && signature.getReturnType() == String.class;
    }

    public static String maskArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return "[]";
        }
        return Arrays.stream(args)
                .map(ToolAuditAspect::describe)
                .collect(Collectors.joining(", ", "[", "]"));
    }

    /**
     * 로그로 나가기 전 마지막 관문.
     * 정규식 마스킹은 완벽하지 않다 — 형식이 어긋난 개인정보는 그물을 빠져나간다.
     * 그러니 이건 최후 방어선이고, 애초에 민감정보를 인자로 받지 않는 도구 설계가 먼저다.
     */
    public static String mask(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        String masked = CARD_NUMBER.matcher(raw).replaceAll("****-****-****-****");
        masked = RESIDENT_NUMBER.matcher(masked).replaceAll("******-*******");
        masked = EMAIL.matcher(masked).replaceAll("***@***");
        return masked;
    }

    private static String describe(Object arg) {
        if (arg == null) {
            return "null";
        }
        String text = mask(String.valueOf(arg));
        if (text.length() > MAX_ARG_LENGTH) {
            return text.substring(0, MAX_ARG_LENGTH) + "...(" + text.length() + "자)";
        }
        return text;
    }

    /**
     * AuditAdvisor 가 심어 둔 추적 ID 를 이어받는다.
     * 같은 요청에서 나온 로그가 한 줄로 꿰어져야 "이 대화에서 이 도구가 불렸다"를 재구성할 수 있다.
     * 없으면 임시 ID 를 만들되 MDC 에는 넣지 않는다 — 여기서 넣으면 지울 책임까지 떠안고,
     * 스레드 풀에서 지우지 못한 MDC 는 다음 요청의 로그에 남의 추적 ID 를 붙인다.
     */
    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId != null ? traceId : "t-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 인증 주체는 서버가 확정한 값이다. 프롬프트에 실려 온 계정 ID 를 믿지 않는다. */
    private String currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return "anonymous";
        }
        return authentication.getName();
    }

    /** @Tool 에 이름을 붙였으면 그 이름으로 집계한다 — 모델이 부르는 이름과 지표의 이름이 같아야 대조가 된다. */
    private String toolNameOf(ProceedingJoinPoint joinPoint) {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Tool tool = AnnotatedElementUtils.findMergedAnnotation(method, Tool.class);
        if (tool != null && !tool.name().isBlank()) {
            return tool.name();
        }
        return method.getName();
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
