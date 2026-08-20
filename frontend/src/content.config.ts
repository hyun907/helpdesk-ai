import { defineCollection } from 'astro:content';
import { glob } from 'astro/loaders';

/**
 * 운영 정책 문서.
 *
 * base 가 백엔드 리소스를 직접 가리킨다 — 복사본을 만들지 않는다.
 * IngestService 가 벡터로 색인하는 파일과 이 사이트가 렌더하는 파일이
 * 물리적으로 같은 하나여야, 답변에 붙는 "출처" 링크가 거짓말을 하지 않는다.
 * 복사해 두면 정책을 고칠 때 한쪽만 고쳐서 인용과 원문이 어긋난다.
 *
 * 읽기 전용 참조다. 이 프로젝트는 백엔드 트리에 아무것도 쓰지 않는다.
 */
const policies = defineCollection({
  loader: glob({ pattern: '**/*.md', base: '../src/main/resources/docs' }),
});

export const collections = { policies };
