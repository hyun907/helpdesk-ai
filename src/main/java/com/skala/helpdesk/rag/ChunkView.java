package com.skala.helpdesk.rag;

/**
 * 인제스트 결과를 눈으로 확인하기 위한 조회 모델.
 * 유사도 점수를 반드시 노출한다 — 임계값을 감으로 정하지 않기 위해서다.
 */
public record ChunkView(String source, String heading, String version, Double score, String preview) {
}
