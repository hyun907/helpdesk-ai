package com.skala.helpdesk.rag;

import io.swagger.v3.oas.annotations.media.Schema;

/** 인제스트 결과 — 성공 메시지가 아니라 숫자로 확인한다. */
public record IngestResult(
        @Schema(example = "return-policy.md") String source,
        @Schema(example = "policy") String docType,
        @Schema(example = "CS") String dept,
        @Schema(example = "7") int chunks) {
}
