import { useState } from 'react';

/**
 * 검색 점검 창구.
 *
 * 목적은 "답변이 왜 그렇게 나왔는가"를 답변 만들기 전에 확인하는 것이다.
 * 그래서 점수를 숨기지 않고, 인접 결과 사이의 낙차까지 함께 보여준다 —
 * 임계값은 보통 점수가 뚝 떨어지는 자리에 있고, 그 자리는 숫자 나열만으로는 잘 안 보인다.
 */
export default function ChunkInspector() {
  const [q, setQ] = useState('');
  const [topK, setTopK] = useState(5);
  const [state, setState] = useState({ status: 'idle' });
  const [ingest, setIngest] = useState({ status: 'idle' });

  async function search(e) {
    e.preventDefault();
    const query = q.trim();
    if (!query) return;

    setState({ status: 'loading' });
    try {
      const res = await fetch(
        `/api/admin/chunks?q=${encodeURIComponent(query)}&topK=${topK}`,
      );
      if (!res.ok) {
        setState({ status: 'error', error: describe(res.status) });
        return;
      }
      setState({ status: 'done', rows: await res.json(), query });
    } catch {
      // fetch 자체가 실패하면 백엔드가 안 떠 있는 경우가 대부분이다.
      setState({ status: 'error', error: BACKEND_DOWN });
    }
  }

  async function runIngest() {
    // 두 번 눌러야 실행된다. 벡터 스토어를 다시 쓰는 동작이라 오클릭으로 돌면 곤란하다.
    // window.confirm 은 쓰지 않는다 — 브라우저 모달은 페이지를 통째로 막는다.
    if (ingest.status !== 'confirm') {
      setIngest({ status: 'confirm' });
      return;
    }
    setIngest({ status: 'running' });
    try {
      const res = await fetch('/api/admin/ingest', { method: 'POST' });
      if (!res.ok) {
        setIngest({ status: 'error', error: describe(res.status) });
        return;
      }
      setIngest({ status: 'done', results: await res.json() });
    } catch {
      setIngest({ status: 'error', error: BACKEND_DOWN });
    }
  }

  return (
    <div className="inspector">
      <form className="controls" onSubmit={search}>
        <input
          type="search"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          onKeyDown={onEnter}
          placeholder="예: 아이템 복구 기한"
          aria-label="검색어"
        />
        <label className="topk">
          topK
          <input
            type="number"
            min="1"
            max="20"
            value={topK}
            onChange={(e) => setTopK(Number(e.target.value))}
          />
        </label>
        <button type="submit" disabled={state.status === 'loading' || !q.trim()}>
          {state.status === 'loading' ? '검색 중…' : '검색'}
        </button>
      </form>

      <div className="ingest-row">
        <button
          type="button"
          className={ingest.status === 'confirm' ? 'danger' : 'ghost'}
          onClick={runIngest}
          disabled={ingest.status === 'running'}
        >
          {ingest.status === 'running'
            ? '인제스트 중…'
            : ingest.status === 'confirm'
              ? '정말 실행합니다 — 한 번 더'
              : '문서 인제스트'}
        </button>
        {ingest.status === 'confirm' && (
          <>
            <span className="hint">벡터 스토어를 재색인합니다.</span>
            <button type="button" className="ghost" onClick={() => setIngest({ status: 'idle' })}>
              취소
            </button>
          </>
        )}
        {ingest.status === 'done' && (
          <span className="hint ok">
            {ingest.results.map((r) => `${r.source} ${r.chunks}청크`).join(' · ')}
          </span>
        )}
        {ingest.status === 'error' && <span className="hint bad">{ingest.error}</span>}
      </div>

      {state.status === 'error' && <p className="notice bad">{state.error}</p>}

      {state.status === 'done' && state.rows.length === 0 && (
        <p className="notice">
          <strong>“{state.query}”</strong> 에 대해 검색된 청크가 없습니다.
          인제스트를 아직 실행하지 않았을 수 있습니다.
        </p>
      )}

      {state.status === 'done' && state.rows.length > 0 && (
        <Results rows={state.rows} />
      )}

      {state.status === 'idle' && (
        <p className="notice">
          검색어를 넣으면 벡터 스토어에서 실제로 무엇이 검색되는지 점수와 함께 보여줍니다.
        </p>
      )}
    </div>
  );
}

function Results({ rows }) {
  return (
    <ol className="results">
      {rows.map((row, i) => {
        // 앞 순위와의 낙차. 임계값을 정할 자리는 대개 여기가 크게 벌어지는 지점이다.
        const drop = i > 0 ? rows[i - 1].score - row.score : null;
        return (
          <li key={i}>
            <div className="rank">{i + 1}</div>
            <div className="body">
              <div className="head">
                <a href={docHref(row)}>{row.source}</a>
                {row.heading && <span className="heading">{row.heading}</span>}
                <span className="version">{row.version}</span>
              </div>

              <div className="score">
                <div className="bar" aria-hidden="true">
                  <span style={{ width: `${clamp(row.score) * 100}%` }} />
                </div>
                <span className="num">{fmt(row.score)}</span>
                {drop !== null && (
                  <span className={`drop${drop > 0.05 ? ' big' : ''}`}>▼ {fmt(drop)}</span>
                )}
              </div>

              <p className="preview">{row.preview}</p>
            </div>
          </li>
        );
      })}
    </ol>
  );
}

/**
 * 한글 입력 중의 Enter 는 조합을 확정하는 키다.
 * 그대로 두면 "환불" 의 마지막 글자를 확정하는 Enter 가 폼 제출까지 밀고 들어가,
 * 사용자가 아직 다 치지도 않은 검색어로 요청이 나가거나 페이지가 리로드된다.
 * 조합 중이면 삼킨다 — 확정 후 한 번 더 누르면 그때 검색된다.
 */
function onEnter(e) {
  if (e.key === 'Enter' && e.nativeEvent.isComposing) {
    e.preventDefault();
  }
}

/**
 * 청크가 나온 조항으로 바로 보내는 링크.
 *
 * 백엔드가 heading 을 함께 내려주므로 문서 첫머리가 아니라 그 조항으로 꽂을 수 있다.
 * 앵커는 Astro 의 마크다운 헤딩 id 와 같은 규칙(github-slugger)으로 만든다 —
 * 문장부호를 떼고 공백을 하이픈으로 바꾼다. "4. 환불이 제한되는 경우" → "4-환불이-제한되는-경우"
 *
 * heading 이 없으면 문서 첫머리로 보낸다. 링크가 깨지지는 않는다.
 */
function docHref(row) {
  const doc = `/docs/${row.source.replace(/\.md$/, '')}`;
  if (!row.heading) return doc;
  const anchor = row.heading
    .toLowerCase()
    .trim()
    .replace(/[^\p{L}\p{N}\s-]/gu, '')
    .replace(/\s+/g, '-');
  return `${doc}#${anchor}`;
}

const BACKEND_DOWN =
  '백엔드(localhost:8081)에 연결할 수 없습니다. ./gradlew bootRun 으로 먼저 띄워 주세요.';

function describe(status) {
  if (status === 401 || status === 403) {
    return '관리자 인증이 필요합니다 — ADMIN_BASIC 환경변수를 넣고 dev 서버를 다시 띄우세요.';
  }
  // 개발 중에는 프록시가 업스트림 연결 실패를 502 로 바꿔 준다.
  // fetch 가 예외를 던지지 않으므로 여기서 잡아야 원인을 제대로 말할 수 있다.
  if (status === 502 || status === 503 || status === 504) return BACKEND_DOWN;
  if (status === 404) return '엔드포인트를 찾을 수 없습니다. 백엔드 경로가 바뀌었는지 확인하세요.';
  return `요청이 실패했습니다 (HTTP ${status}).`;
}

const fmt = (n) => (typeof n === 'number' ? n.toFixed(3) : '—');
const clamp = (n) => Math.max(0, Math.min(1, typeof n === 'number' ? n : 0));
