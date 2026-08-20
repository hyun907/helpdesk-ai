package com.skala.helpdesk.rag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * 문서 인제스트 — 읽기 → 분할 → 메타데이터 보강 → 저장.
 *
 * 이 단계에서 두 가지를 반드시 지킨다.
 *   1. 재색인: 같은 출처를 지우고 다시 넣는다. 안 하면 같은 청크가 쌓인다.
 *   2. 메타데이터: 인제스트 시점에만 넣을 수 있다. 빠뜨리면 전량 재색인해야 한다.
 *
 * 둘 다 오류를 내지 않는다. 검색 품질만 조용히 나빠지므로 원인 추적이 가장 어렵다.
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    /**
     * 조항 하나가 토큰 상한을 넘을 때만 추가로 자른다.
     * 규정 문서는 크기보다 조항 경계가 우선이므로, 기본 경로는 길이 분할이 아니다.
     */
    private static final int CHUNK_SIZE = 800;
    private static final int MIN_CHUNK_SIZE_CHARS = 200;

    /** 문서 제목(# ...)과 조항 제목(## ...). 조항이 검색 단위가 된다. */
    private static final Pattern TITLE = Pattern.compile("(?m)^#\\s+(.+)$");
    private static final Pattern SECTION = Pattern.compile("(?m)^##\\s+(.+)$");

    /** 이보다 짧은 조항은 앞 조항에 붙인다. 한두 문장짜리 조각은 검색에 걸려도 답이 안 된다. */
    private static final int MIN_SECTION_CHARS = 120;

    private final VectorStore vectorStore;

    public IngestService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public IngestResult ingest(Resource file, String docType, String dept) {
        String source = file.getFilename();
        String version = LocalDate.now().toString();

        // ① 재색인 — 같은 출처를 먼저 지운다
        vectorStore.delete(new FilterExpressionBuilder().eq("source", source).build());

        // ② 분할 — 규정 문서는 조항(##)이 검색 단위다
        List<Chunk> chunks = isMarkdown(source) ? splitBySection(file) : splitByLength(file);

        // ③ 메타데이터 보강 — 출처 표기·필터·조항 앵커가 전부 여기서 나온다
        List<Document> enriched = chunks.stream().map(chunk -> {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("source", source);        // 출처 표기용
            metadata.put("docType", docType);      // 필터용
            metadata.put("dept", dept);            // 권한·범위 제한용
            metadata.put("version", version);      // 최신본 판별용
            metadata.put("heading", chunk.heading());   // 조항 앵커용 — 지금 안 넣으면 전량 재색인이다
            return new Document(chunk.text(), metadata);
        }).toList();

        // ④ 저장 — 임베딩 호출은 VectorStore 가 알아서 한다
        vectorStore.add(enriched);

        log.info("인제스트 완료 source={} docType={} dept={} chunks={}",
                source, docType, dept, enriched.size());
        return new IngestResult(source, docType, dept, enriched.size());
    }

    private boolean isMarkdown(String source) {
        return source != null && source.toLowerCase().endsWith(".md");
    }

    /**
     * 조항 단위 분할.
     *
     * <p>길이로 자르면 조항 하나가 통째로 한 청크에 들어가지 않거나, 반대로 여러 조항이
     * 한 청크에 뭉친다. 뭉치면 유사도가 희석돼 정작 그 조항을 묻는 질문이 임계값을 못 넘는다.
     * 실제로 800토큰 길이 분할에서는 문서 하나가 청크 하나가 되어 최고 점수가 0.36까지 떨어졌다.
     *
     * <p>각 청크 앞에 문서 제목을 붙인다. "3회 위반 시 이용 정지"라는 표만 떼어 놓으면
     * 무엇에 대한 규정인지 알 수 없어, 검색에도 답변 생성에도 불리하다.
     */
    private List<Chunk> splitBySection(Resource file) {
        String raw = read(file);
        String title = firstMatch(TITLE, raw);

        List<Chunk> chunks = new ArrayList<>();
        Matcher matcher = SECTION.matcher(raw);

        List<int[]> bounds = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        while (matcher.find()) {
            bounds.add(new int[]{matcher.start(), matcher.end()});
            headings.add(matcher.group(1).trim());
        }

        if (bounds.isEmpty()) {              // 조항 구분이 없는 문서는 길이 분할로 돌아간다
            return splitByLength(file);
        }

        for (int i = 0; i < bounds.size(); i++) {
            int start = bounds.get(i)[0];
            int end = (i + 1 < bounds.size()) ? bounds.get(i + 1)[0] : raw.length();
            String body = raw.substring(start, end).trim();

            // 너무 짧은 조항은 앞 조항에 붙인다
            if (body.length() < MIN_SECTION_CHARS && !chunks.isEmpty()) {
                Chunk previous = chunks.remove(chunks.size() - 1);
                chunks.add(new Chunk(previous.text() + "\n\n" + body, previous.heading()));
                continue;
            }
            chunks.add(new Chunk(withTitle(title, body), headings.get(i)));
        }
        return splitOversized(chunks);
    }

    /** 조항 하나가 토큰 상한을 넘으면 그 안에서만 다시 자른다. 조항 제목은 그대로 물려준다. */
    private List<Chunk> splitOversized(List<Chunk> chunks) {
        var splitter = TokenTextSplitter.builder()
                .withChunkSize(CHUNK_SIZE)
                .withMinChunkSizeChars(MIN_CHUNK_SIZE_CHARS)
                .build();

        List<Chunk> result = new ArrayList<>();
        for (Chunk chunk : chunks) {
            List<Document> pieces = splitter.apply(List.of(new Document(chunk.text())));
            if (pieces.size() <= 1) {
                result.add(chunk);
                continue;
            }
            pieces.forEach(piece -> result.add(new Chunk(piece.getText(), chunk.heading())));
        }
        return result;
    }

    /** 마크다운이 아닌 문서용 경로. 조항 개념이 없으므로 heading 은 비운다. */
    private List<Chunk> splitByLength(Resource file) {
        List<Document> raw = new TikaDocumentReader(file).get();
        return TokenTextSplitter.builder()
                .withChunkSize(CHUNK_SIZE)
                .withMinChunkSizeChars(MIN_CHUNK_SIZE_CHARS)
                .build()
                .apply(raw)
                .stream()
                .map(d -> new Chunk(d.getText(), null))
                .toList();
    }

    private String withTitle(String title, String body) {
        return title == null ? body : "[" + title + "]\n\n" + body;
    }

    private String firstMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private String read(Resource file) {
        try {
            return file.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("문서를 읽지 못했습니다: " + file.getFilename(), e);
        }
    }

    /** 분할 결과 한 조각. 본문과 그 조각이 속한 조항 제목을 함께 들고 다닌다. */
    private record Chunk(String text, String heading) {}

    /**
     * 무엇이 들어갔는지 눈으로 본다.
     * 답변을 만들기 전에 이 창구부터 열어 둔다 — 검색이 잘못됐는데 프롬프트를 고치는 일을 막는다.
     */
    public List<ChunkView> inspect(String query, int topK) {
        return vectorStore.similaritySearch(SearchRequest.builder()
                        .query(query)
                        .topK(topK)
                        .build())
                .stream()
                .map(d -> new ChunkView(
                        (String) d.getMetadata().get("source"),
                        (String) d.getMetadata().get("heading"),
                        (String) d.getMetadata().get("version"),
                        d.getScore(),
                        preview(d.getText())))
                .toList();
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        String flattened = text.replaceAll("\\s+", " ").trim();
        return flattened.length() <= 160 ? flattened : flattened.substring(0, 160) + "…";
    }
}
