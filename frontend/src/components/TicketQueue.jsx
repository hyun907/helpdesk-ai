import { useCallback, useEffect, useState } from 'react';
import { apiFetch } from '../lib/auth.js';

/**
 * 승인 대기 큐.
 *
 * 도구는 접수(PENDING)까지만 만들고, 여기서부터는 사람이 누른다.
 * 승인·반려에는 되돌리는 경로가 없다 — 백엔드가 이미 처리된 티켓을 409 로 막는다.
 * 그래서 두 번 눌러야 실행되고, 무엇을 하려는지 버튼에 그대로 쓴다.
 */
export default function TicketQueue() {
  const [state, setState] = useState({ status: 'loading' });
  const [pending, setPending] = useState(null);   // 확인 대기 중인 {no, action}
  const [working, setWorking] = useState(null);   // 처리 중인 no
  const [done, setDone] = useState({});           // no → {status} | {error}

  const load = useCallback(async () => {
    setState({ status: 'loading' });
    try {
      const res = await apiFetch('/api/admin/tickets/pending');
      if (!res.ok) return setState({ status: 'error', error: describe(res.status) });
      setState({ status: 'done', rows: await res.json() });
      setDone({});
      setPending(null);
    } catch {
      setState({ status: 'error', error: BACKEND_DOWN });
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  async function resolve(no, action) {
    // 첫 클릭은 확인만 받는다. 되돌릴 수 없는 동작이라 한 번 더 묻는다.
    if (pending?.no !== no || pending?.action !== action) {
      setPending({ no, action });
      return;
    }

    setPending(null);
    setWorking(no);
    try {
      const res = await apiFetch(`/api/admin/tickets/${encodeURIComponent(no)}/${action}`, {
        method: 'POST',
      });
      if (!res.ok) {
        setDone((d) => ({ ...d, [no]: { error: describe(res.status) } }));
        return;
      }
      const ticket = await res.json();
      setDone((d) => ({ ...d, [no]: { status: ticket.status } }));
    } catch {
      setDone((d) => ({ ...d, [no]: { error: BACKEND_DOWN } }));
    } finally {
      setWorking(null);
    }
  }

  if (state.status === 'loading') {
    return <p className="notice">불러오는 중…</p>;
  }
  if (state.status === 'error') {
    return (
      <div>
        <p className="notice bad">{state.error}</p>
        <button type="button" className="ghost" onClick={load}>다시 시도</button>
      </div>
    );
  }

  const rows = state.rows;

  return (
    <div className="queue">
      <div className="queue-head">
        <span className="count">
          승인 대기 <strong>{rows.length}</strong>건
        </span>
        <button type="button" className="ghost" onClick={load}>새로고침</button>
      </div>

      {rows.length === 0 ? (
        <p className="notice">승인 대기 중인 신청이 없습니다.</p>
      ) : (
        <ul className="tickets">
          {rows.map((t) => {
            const result = done[t.no];
            const busy = working === t.no;
            return (
              <li key={t.no} className={result?.status ? 'resolved' : undefined}>
                <div className="t-head">
                  <code className="no">{t.no}</code>
                  <span className={`type ${t.type === '제재 이의신청' ? 'appeal' : 'recovery'}`}>
                    {t.type}
                  </span>
                  <span className="when" title={t.createdAt}>{ago(t.createdAt)}</span>
                </div>

                <p className="detail">{t.detail}</p>

                {result?.status ? (
                  <p className="outcome">처리 완료 — <strong>{result.status}</strong></p>
                ) : result?.error ? (
                  <p className="outcome bad">{result.error}</p>
                ) : (
                  <div className="actions">
                    <button
                      type="button"
                      className={confirming(pending, t.no, 'approve') ? 'confirm-approve' : 'approve'}
                      onClick={() => resolve(t.no, 'approve')}
                      disabled={busy}
                    >
                      {busy ? '처리 중…' : confirming(pending, t.no, 'approve') ? '승인합니다 — 한 번 더' : '승인'}
                    </button>
                    <button
                      type="button"
                      className={confirming(pending, t.no, 'reject') ? 'confirm-reject' : 'reject'}
                      onClick={() => resolve(t.no, 'reject')}
                      disabled={busy}
                    >
                      {busy ? '' : confirming(pending, t.no, 'reject') ? '반려합니다 — 한 번 더' : '반려'}
                    </button>

                    {pending?.no === t.no && (
                      <>
                        <span className="warn">되돌릴 수 없습니다.</span>
                        <button type="button" className="ghost" onClick={() => setPending(null)}>
                          취소
                        </button>
                      </>
                    )}
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

const confirming = (pending, no, action) => pending?.no === no && pending?.action === action;

const BACKEND_DOWN =
  '백엔드(localhost:8081)에 연결할 수 없습니다. ./gradlew bootRun 으로 먼저 띄워 주세요.';

function describe(status) {
  if (status === 401 || status === 403) {
    return '관리자 권한이 필요합니다. 담당자(GM) 계정으로 로그인해 주세요.';
  }
  // 다른 GM 이 먼저 처리했을 때 나온다. 목록이 낡은 것이므로 새로고침이 답이다.
  if (status === 409) return '이미 처리된 신청입니다. 새로고침해 최신 목록을 확인하세요.';
  if (status === 404) return '없는 신청 번호입니다. 목록이 낡았을 수 있습니다.';
  if (status === 502 || status === 503 || status === 504) return BACKEND_DOWN;
  return `요청이 실패했습니다 (HTTP ${status}).`;
}

/** 접수한 지 얼마나 됐는지 — 큐에서는 절대 시각보다 이쪽이 먼저 읽힌다. */
function ago(iso) {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return '';
  const mins = Math.floor((Date.now() - then) / 60000);
  if (mins < 1) return '방금';
  if (mins < 60) return `${mins}분 전`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}시간 전`;
  return `${Math.floor(hours / 24)}일 전`;
}
