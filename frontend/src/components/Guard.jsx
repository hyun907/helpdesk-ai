import { useEffect, useState } from 'react';
import { read, subscribe, login, clear } from '../lib/auth.js';

/**
 * 로그인 상태를 구독한다.
 *
 * 첫 렌더에서 sessionStorage 를 그대로 읽는다. 이게 가능한 이유는 이 훅을 쓰는
 * 아일랜드를 전부 client:only 로 내렸기 때문이다 — 서버가 미리 그려 둔 HTML 이 없으니
 * 어긋날 상대가 없다. (client:load 시절에는 서버 렌더가 항상 "로그아웃"이라
 * 첫 렌더를 null 로 맞춘 뒤 effect 에서 올려야 했고, 그 한 프레임이 페이지를 이동할 때마다
 * "확인 중…" 으로 깜빡였다.)
 */
export function useAuth() {
  const [cred, setCred] = useState(read);

  useEffect(() => {
    // 첫 렌더와 이 시점 사이에 값이 바뀌었을 수 있다(다른 아일랜드의 로그인/로그아웃).
    setCred(read());
    return subscribe(setCred);
  }, []);

  return cred;
}

/**
 * 인증 게이트를 씌운 컴포넌트를 만든다.
 *
 * 왜 HOC 인가 — Astro 로 감싸지 않기 위해서다.
 * `<Guard client:only><Chat /></Guard>` 처럼 .astro 에서 중첩하면 안쪽 Chat 은
 * React 엘리먼트가 아니라 Astro 가 미리 그려 둔 정적 HTML 로 들어온다. 그러면
 * 화면은 멀쩡해 보이는데 onChange·onSubmit 이 아무 데도 붙지 않아 입력이 통째로 죽는다.
 * (겪고 나서야 보이는 종류의 사고다 — 눌리지 않는 이유가 화면에는 드러나지 않는다.)
 *
 * 그래서 감싸는 일을 React 안에서 끝낸다. 아일랜드는 하나, 경계도 하나다.
 *
 * 이건 화면 편의이지 보안 장치가 아니다. 실제 차단은 서버가 한다 —
 * 이 게이트를 개발자도구로 걷어내도 /api/admin/** 은 여전히 403 이다.
 * 여기서 막는 이유는 권한 없는 사용자가 눌러 봐야 전부 실패하는 화면에
 * 들어가지 않게 하려는 것뿐이다.
 */
export function withGuard(Inner, { admin = false } = {}) {
  return function Guarded(props) {
    return (
      <Guard admin={admin}>
        <Inner {...props} />
      </Guard>
    );
  };
}

function Guard({ admin = false, children }) {
  const cred = useAuth();

  if (!cred) return <LoginForm admin={admin} />;

  if (admin && !cred.admin) {
    return (
      <div className="auth-panel px">
        <h2>권한이 없습니다</h2>
        <p>
          <code>{cred.username}</code> 계정은 관리자가 아닙니다. 이 화면은 담당자(GM) 전용입니다.
        </p>
        <p className="hint">서버도 같은 판단을 합니다 — 이 화면을 지나가도 요청은 403 으로 막힙니다.</p>
        <button type="button" className="ghost" onClick={clear}>
          다른 계정으로 로그인
        </button>
      </div>
    );
  }

  return children;
}

function LoginForm({ admin }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [state, setState] = useState({ status: 'idle' });

  async function submit(e) {
    e.preventDefault();
    if (!username.trim() || !password) return;

    setState({ status: 'working' });
    const result = await login(username.trim(), password);
    if (!result.ok) {
      setState({ status: 'error', error: result.error });
      // 비밀번호만 비운다. 계정명은 남겨 둬야 오타를 고칠 때 다시 치지 않는다.
      setPassword('');
      return;
    }
    // 성공하면 구독을 통해 Guard 가 다시 그려진다 — 여기서 따로 할 일이 없다.
  }

  return (
    <form className="auth-panel px" onSubmit={submit}>
      <h2>로그인</h2>
      <p>
        {admin
          ? '담당자(GM) 계정으로 로그인하면 이 화면을 쓸 수 있습니다.'
          : '상담을 시작하려면 로그인이 필요합니다.'}
      </p>

      <label>
        계정
        <input
          type="text"
          value={username}
          autoComplete="username"
          onChange={(e) => setUsername(e.target.value)}
          disabled={state.status === 'working'}
        />
      </label>

      <label>
        비밀번호
        <input
          type="password"
          value={password}
          autoComplete="current-password"
          onChange={(e) => setPassword(e.target.value)}
          disabled={state.status === 'working'}
        />
      </label>

      <button type="submit" disabled={state.status === 'working' || !username.trim() || !password}>
        {state.status === 'working' ? '확인 중…' : '로그인'}
      </button>

      {state.status === 'error' && <p className="notice bad">{state.error}</p>}

      <p className="hint">
        자격증명은 이 탭에만 보관되며 탭을 닫으면 사라집니다. 서버로는 요청마다 다시 실려 갑니다.
      </p>
    </form>
  );
}
