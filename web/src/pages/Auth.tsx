import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { api, setTokens, ApiError } from '../api';
import { Alert, Button, Card, Field, errorText } from '../ui';

function Shell({ title, sub, children }: { title: string; sub?: string; children: React.ReactNode }) {
  return (
    <main className="auth">
      <div className="auth-box">
        <p className="brand">🎬 보조출연 헬프</p>
        <Card>
          <h1 className="auth-t">{title}</h1>
          {sub && <p className="cap" style={{ marginBottom: 12 }}>{sub}</p>}
          {children}
        </Card>
      </div>
    </main>
  );
}

interface LoginRes {
  status?: string;
  accessToken?: string;
  refreshToken?: string | null;
  passwordChangeRequired?: boolean;
}

/** 업체·관리자 로그인. 이메일(또는 관리자 아이디)+비밀번호, 관리자일 때만 2차 비밀번호 칸이 나타난다. */
export function Login() {
  const nav = useNavigate();
  const [id, setId] = useState('');
  const [pw, setPw] = useState('');
  const [second, setSecond] = useState('');
  const [needSecond, setNeedSecond] = useState(false);
  const [err, setErr] = useState('');
  const [locked, setLocked] = useState(false);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setErr('');
    try {
      const r = await api.post<LoginRes>('/auth/email/login', { email: id.trim(), password: pw, ...(needSecond ? { secondPassword: second } : {}) });
      if (r.status === 'SECOND_PASSWORD_REQUIRED') {
        setNeedSecond(true);
        return;
      }
      setTokens({ accessToken: r.accessToken!, refreshToken: r.refreshToken });
      nav(r.passwordChangeRequired ? '/change-password' : '/', { replace: true });
    } catch (ex) {
      setLocked(ex instanceof ApiError && ex.status === 429);
      setErr(errorText(ex));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Shell title="🔐 로그인" sub="업체·관리자는 이메일과 비밀번호로 들어와요. 출연자는 앱을 이용해 주세요.">
      <form onSubmit={submit} className="stack">
        <Field label="이메일 (관리자는 아이디)">
          <input value={id} onChange={(e) => setId(e.target.value)} autoComplete="username" required />
        </Field>
        <Field label="비밀번호">
          <input type="password" value={pw} onChange={(e) => setPw(e.target.value)} autoComplete="current-password" required />
        </Field>
        {needSecond && (
          <Field label="2차 비밀번호 (관리자 전용)">
            <input type="password" value={second} onChange={(e) => setSecond(e.target.value)} autoComplete="off" autoFocus required />
          </Field>
        )}
        {err && <Alert tone="bad">{locked ? `${err} 비밀번호를 5번 잘못 입력하면 이 접속 위치에서 15분 동안 막혀요. 계정 주인에게 알림 메일도 가요.` : err}</Alert>}
        <Button type="submit" busy={busy}>
          로그인
        </Button>
        <div className="row-links">
          <Link to="/forgot">비밀번호를 잊었어요</Link>
          <Link to="/signup">업체 가입하기</Link>
        </div>
      </form>
    </Shell>
  );
}

export function Signup() {
  const nav = useNavigate();
  const [f, setF] = useState({ email: '', name: '', password: '', agree: false });
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setErr('');
    if (!f.agree) return setErr('필수 약관에 동의해 주세요.');
    setBusy(true);
    try {
      const terms = await api.get<{ id: number; type: string }[]>('/terms');
      const ids = terms.filter((t) => t.type === 'SERVICE' || t.type === 'PRIVACY').map((t) => t.id);
      const r = await api.post<{ accessToken: string; refreshToken: string }>('/auth/email/signup', { email: f.email.trim(), password: f.password, name: f.name.trim(), agreedTermIds: ids });
      setTokens({ accessToken: r.accessToken, refreshToken: r.refreshToken });
      nav('/verify-sent', { replace: true });
    } catch (ex) {
      setErr(errorText(ex));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Shell title="📝 업체 가입" sub="가입하면 확인 메일을 보내 드려요. 메일 확인 뒤 업체를 등록하면 관리자가 승인해요.">
      <form onSubmit={submit} className="stack">
        <Field label="이름">
          <input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} required maxLength={50} />
        </Field>
        <Field label="이메일">
          <input type="email" value={f.email} onChange={(e) => setF({ ...f, email: e.target.value })} required autoComplete="email" />
        </Field>
        <Field label="비밀번호" hint="10자 이상, 영문과 숫자를 함께 써 주세요.">
          <input type="password" value={f.password} onChange={(e) => setF({ ...f, password: e.target.value })} required minLength={10} autoComplete="new-password" />
        </Field>
        <label className="check">
          <input type="checkbox" checked={f.agree} onChange={(e) => setF({ ...f, agree: e.target.checked })} />
          <span>(필수) 서비스 이용약관과 개인정보 수집·이용에 동의해요</span>
        </label>
        {err && <Alert tone="bad">{err}</Alert>}
        <Button type="submit" busy={busy}>
          가입하기
        </Button>
        <div className="row-links">
          <Link to="/login">이미 계정이 있어요</Link>
        </div>
      </form>
    </Shell>
  );
}

/** 가입 직후 안내 + 확인 메일 다시 받기. */
export function VerifySent() {
  const [msg, setMsg] = useState('');
  const [bad, setBad] = useState(false);
  async function resend() {
    try {
      await api.post('/auth/email/resend-verification');
      setBad(false);
      setMsg('확인 메일을 다시 보냈어요. 스팸함도 확인해 주세요.');
    } catch (ex) {
      setBad(true);
      setMsg(errorText(ex));
    }
  }
  return (
    <Shell title="📧 이메일을 확인해 주세요" sub="보낸 메일의 주소를 눌러 이메일 확인을 마쳐 주세요. 24시간 안에 한 번만 쓸 수 있어요.">
      <div className="stack">
        <Button variant="line" onClick={resend}>
          확인 메일 다시 받기
        </Button>
        {msg && <Alert tone={bad ? 'bad' : 'ok'}>{msg}</Alert>}
        <Link className="btn primary md" to="/">
          메일을 확인했어요
        </Link>
      </div>
    </Shell>
  );
}

/** 메일 속 링크(/verify-email?token=…)가 여는 화면. */
export function VerifyEmail() {
  const [params] = useSearchParams();
  const [state, setState] = useState<'idle' | 'ok' | 'bad'>('idle');
  const [busy, setBusy] = useState(false);
  async function run() {
    setBusy(true);
    try {
      await api.post('/auth/email/verify', { token: params.get('token') ?? '' });
      setState('ok');
    } catch {
      setState('bad');
    } finally {
      setBusy(false);
    }
  }
  return (
    <Shell title="✅ 이메일 확인">
      <div className="stack">
        {state === 'idle' && (
          <>
            <p>아래 버튼을 눌러 이메일 확인을 마쳐요.</p>
            <Button onClick={run} busy={busy}>
              이메일 확인하기
            </Button>
          </>
        )}
        {state === 'ok' && <Alert tone="ok">확인이 끝났어요! 이제 업체를 등록할 수 있어요.</Alert>}
        {state === 'bad' && <Alert tone="bad">링크가 만료됐거나 이미 사용했어요. 로그인 뒤 확인 메일을 다시 받아 주세요.</Alert>}
        <Link className="btn line md" to="/">
          처음 화면으로
        </Link>
      </div>
    </Shell>
  );
}

export function Forgot() {
  const [email, setEmail] = useState('');
  const [sent, setSent] = useState(false);
  const [busy, setBusy] = useState(false);
  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    try {
      await api.post('/auth/password/reset-request', { email: email.trim() });
    } finally {
      setSent(true);   // 계정이 있든 없든 같은 안내(계정 유무를 숨긴다)
      setBusy(false);
    }
  }
  return (
    <Shell title="🔑 임시 비밀번호 받기" sub="가입한 이메일을 적으면 임시 비밀번호를 보내 드려요. 30분 안에 로그인해 새 비밀번호로 바꿔 주세요.">
      {sent ? (
        <div className="stack">
          <Alert tone="ok">메일을 보냈어요. (가입한 이메일이라면 곧 도착해요)</Alert>
          <Link className="btn primary md" to="/login">
            임시 비밀번호로 로그인하기
          </Link>
        </div>
      ) : (
        <form onSubmit={submit} className="stack">
          <Field label="이메일">
            <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
          </Field>
          <Button type="submit" busy={busy}>
            임시 비밀번호 보내기
          </Button>
          <Link to="/login">로그인으로 돌아가기</Link>
        </form>
      )}
    </Shell>
  );
}

/** 임시 비밀번호로 들어온 뒤에는 이 화면만 쓸 수 있다. */
export function ChangePassword() {
  const nav = useNavigate();
  const [a, setA] = useState('');
  const [b, setB] = useState('');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  async function submit(e: FormEvent) {
    e.preventDefault();
    setErr('');
    if (a !== b) return setErr('두 비밀번호가 달라요.');
    setBusy(true);
    try {
      await api.put('/me/password', { newPassword: a });
      setTokens(null);
      nav('/login', { replace: true, state: { changed: true } });
    } catch (ex) {
      setErr(errorText(ex));
    } finally {
      setBusy(false);
    }
  }
  return (
    <Shell title="🔑 새 비밀번호로 바꿔 주세요" sub="임시 비밀번호로 로그인했어요. 새 비밀번호로 바꾸기 전에는 다른 화면을 쓸 수 없어요.">
      <form onSubmit={submit} className="stack">
        <Field label="새 비밀번호" hint="10자 이상, 영문과 숫자를 함께 써 주세요. 바꾸면 모든 기기에서 로그아웃돼요.">
          <input type="password" value={a} onChange={(e) => setA(e.target.value)} required minLength={10} autoComplete="new-password" />
        </Field>
        <Field label="새 비밀번호 다시 입력">
          <input type="password" value={b} onChange={(e) => setB(e.target.value)} required minLength={10} autoComplete="new-password" />
        </Field>
        {err && <Alert tone="bad">{err}</Alert>}
        <Button type="submit" busy={busy}>
          비밀번호 바꾸기
        </Button>
      </form>
    </Shell>
  );
}
