// 백엔드 호출 도우미. 로그인 토큰은 브라우저 저장소에 두고, 만료되면 한 번 갱신한 뒤 다시 시도한다.
// (프런트는 OpenAPI 생성 클라이언트를 쓰기로 했지만 지금은 단순한 수제 클라이언트로 시작한다 — `npm run api:gen` 으로 교체 가능)

const BASE = (import.meta.env.VITE_API_BASE as string | undefined) ?? '/api';
const STORE = 'bh_tokens';

export interface Tokens {
  accessToken: string;
  refreshToken?: string | null;
}

export class ApiError extends Error {
  status: number;
  code?: string;
  body: Record<string, unknown>;
  constructor(status: number, body: Record<string, unknown>) {
    super(typeof body.detail === 'string' ? body.detail : `요청에 실패했어요 (${status})`);
    this.status = status;
    this.code = typeof body.code === 'string' ? body.code : undefined;
    this.body = body;
  }
}

let tokens: Tokens | null = load();
const listeners = new Set<() => void>();

function load(): Tokens | null {
  try {
    const raw = localStorage.getItem(STORE);
    return raw ? (JSON.parse(raw) as Tokens) : null;
  } catch {
    return null;
  }
}

export function setTokens(t: Tokens | null) {
  tokens = t;
  try {
    if (t) localStorage.setItem(STORE, JSON.stringify(t));
    else localStorage.removeItem(STORE);
  } catch {
    /* 저장소를 못 쓰는 환경(시크릿 모드 등)에서도 메모리에서는 동작한다 */
  }
  listeners.forEach((l) => l());
}

export const getTokens = () => tokens;
export const onTokensChange = (fn: () => void) => {
  listeners.add(fn);
  return () => listeners.delete(fn);
};

let refreshing: Promise<boolean> | null = null;

async function refresh(): Promise<boolean> {
  if (!tokens?.refreshToken) return false;
  refreshing ??= (async () => {
    try {
      const res = await fetch(`${BASE}/auth/refresh`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ refreshToken: tokens?.refreshToken }) });
      if (!res.ok) return false;
      setTokens((await res.json()) as Tokens);
      return true;
    } catch {
      return false;
    } finally {
      refreshing = null;
    }
  })();
  return refreshing;
}

async function request<T>(method: string, path: string, body?: unknown, retry = true): Promise<T> {
  const headers: Record<string, string> = {};
  let payload: BodyInit | undefined;
  if (body instanceof FormData) payload = body;
  else if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    payload = JSON.stringify(body);
  }
  if (tokens?.accessToken) headers.Authorization = `Bearer ${tokens.accessToken}`;
  const res = await fetch(`${BASE}${path}`, { method, headers, body: payload });
  if (res.status === 401 && retry && tokens?.refreshToken && (await refresh())) return request<T>(method, path, body, false);
  if (res.status === 401 && tokens) setTokens(null);
  if (!res.ok) {
    let b: Record<string, unknown> = {};
    try {
      b = (await res.json()) as Record<string, unknown>;
    } catch {
      /* 본문 없음 */
    }
    throw new ApiError(res.status, b);
  }
  if (res.status === 204) return undefined as T;
  const ct = res.headers.get('content-type') ?? '';
  return (ct.includes('json') ? await res.json() : undefined) as T;
}

export const api = {
  get: <T>(p: string) => request<T>('GET', p),
  post: <T>(p: string, b?: unknown) => request<T>('POST', p, b ?? {}),
  put: <T>(p: string, b?: unknown) => request<T>('PUT', p, b ?? {}),
  patch: <T>(p: string, b?: unknown) => request<T>('PATCH', p, b ?? {}),
  del: <T>(p: string) => request<T>('DELETE', p),
  upload: <T>(p: string, form: FormData) => request<T>('POST', p, form),
  /** 파일 내려받기(암호화 엑셀 등). */
  async blob(path: string): Promise<Blob> {
    const res = await fetch(`${BASE}${path}`, { headers: tokens?.accessToken ? { Authorization: `Bearer ${tokens.accessToken}` } : {} });
    if (!res.ok) throw new ApiError(res.status, await res.json().catch(() => ({})));
    return res.blob();
  },
  /** 서버가 돌려준 파일 주소(/files/…)는 API 기준 경로다. */
  fileUrl: (u: string) => `${BASE}${u}`,
};

// ----- 선택한 업체(여러 업체에 속한 사용자용) -----
const COMPANY_KEY = 'bh_company';
export const currentCompanyId = (): number | null => {
  try {
    const v = localStorage.getItem(COMPANY_KEY);
    return v ? Number(v) : null;
  } catch {
    return null;
  }
};
export const setCurrentCompanyId = (id: number) => {
  try {
    localStorage.setItem(COMPANY_KEY, String(id));
  } catch {
    /* 무시 */
  }
};
/** /company/... 요청에 선택한 업체를 붙인다. */
export const withCompany = (path: string): string => {
  const id = currentCompanyId();
  if (!id) return path;
  return `${path}${path.includes('?') ? '&' : '?'}companyId=${id}`;
};
