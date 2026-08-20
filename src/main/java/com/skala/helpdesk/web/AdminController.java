package com.skala.helpdesk.web;

import com.skala.helpdesk.rag.ChunkView;
import com.skala.helpdesk.rag.IngestResult;
import com.skala.helpdesk.rag.IngestService;
import com.skala.helpdesk.service.TicketApprovalService;
import com.skala.helpdesk.web.dto.TicketResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 담당자용 창구 — 문서 인제스트와 신청 건 승인.
 *
 * 승인 메서드는 어떤 경우에도 도구(@Tool)로 노출하지 않는다.
 * 모델이 닿을 수 있는 경로에 승인이 있으면 승인 게이트는 존재하지 않는 것과 같다.
 * 여기 있는 것들은 ROLE_ADMIN 만 호출할 수 있고, 도구 목록에는 없다.
 */
@PreAuthorize("hasRole('ADMIN')")
@RestController
@RequestMapping("/api/admin")
@Tag(name = "관리", description = "문서 인제스트 · 청크 확인")
public class AdminController {

    /** 문서와 분류를 함께 정의한다. 분류는 나중에 검색 필터의 재료가 된다. */
    private static final List<DocSpec> DOCUMENTS = List.of(
            new DocSpec("item-recovery-policy.md", "policy", "CS"),
            new DocSpec("sanction-policy.md", "policy", "CS"),
            new DocSpec("terms-of-service.md", "policy", "CS"),
            new DocSpec("refund-policy.md", "policy", "BILLING"));

    private final IngestService ingestService;
    private final TicketApprovalService approvals;
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    public AdminController(IngestService ingestService, TicketApprovalService approvals) {
        this.ingestService = ingestService;
        this.approvals = approvals;
    }

    @PostMapping("/ingest")
    @Operation(summary = "운영 정책 문서 인제스트",
               description = "두 번 실행해도 청크 수가 같아야 정상이다(재색인).")
    public List<IngestResult> ingest() throws IOException {
        return DOCUMENTS.stream().map(spec -> {
            Resource file = resolver.getResource("classpath:/docs/" + spec.fileName());
            return ingestService.ingest(file, spec.docType(), spec.dept());
        }).toList();
    }

    @GetMapping("/chunks")
    @Operation(summary = "검색 결과 확인",
               description = "답변을 만들기 전에 무엇이 검색되는지 눈으로 본다. 점수를 함께 노출한다.")
    public List<ChunkView> chunks(
            @Parameter(description = "검색어", example = "아이템 복구 기한") @RequestParam String q,
            @Parameter(description = "가져올 개수", example = "5") @RequestParam(defaultValue = "5") int topK) {
        return ingestService.inspect(q, topK);
    }

    // ── 신청 건 승인 ────────────────────────────────────────────
    // 도구는 접수(PENDING)까지만 만든다. 여기부터는 사람이 누른다.

    @GetMapping("/tickets/pending")
    @Operation(summary = "승인 대기 목록",
               description = "도구가 접수한 신청 건 중 아직 처리되지 않은 것들이다.")
    public List<TicketResponse> pending() {
        return approvals.pending();
    }

    @PostMapping("/tickets/{no}/approve")
    @Operation(summary = "신청 승인",
               description = "승인된 뒤에야 실제 처리가 시작된다. 모델은 이 경로를 호출할 수 없다.")
    public TicketResponse approve(
            @Parameter(description = "신청 번호", example = "T-0001") @PathVariable String no) {
        return approvals.approve(no);
    }

    @PostMapping("/tickets/{no}/reject")
    @Operation(summary = "신청 반려")
    public TicketResponse reject(
            @Parameter(description = "신청 번호", example = "T-0001") @PathVariable String no) {
        return approvals.reject(no);
    }

    private record DocSpec(String fileName, String docType, String dept) {}
}
