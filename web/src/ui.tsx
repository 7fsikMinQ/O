import { useEffect, useState, type ReactNode } from 'react';
import { ApiError } from './api';

/** 쉬운 말 오류 문구: 서버가 준 detail 을 우선하고, 없으면 상태별 기본 안내. */
export function errorText(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.message && !e.message.startsWith('요청에 실패')) return e.message;
    if (e.status === 401) return '로그인이 필요해요.';
    if (e.status === 403) return '이 작업을 할 권한이 없어요.';
    if (e.status === 404) return '찾을 수 없어요.';
    if (e.status === 429) return '요청이 너무 많아요. 잠시 뒤에 다시 해 주세요.';
    if (e.status >= 500) return '서버에 문제가 생겼어요. 잠시 뒤에 다시 해 주세요.';
  }
  return '연결에 문제가 있어요. 인터넷을 확인해 주세요.';
}

export function Card({ title, right, children, className = '' }: { title?: ReactNode; right?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={`card ${className}`}>
      {(title || right) && (
        <header className="card-h">
          <h2>{title}</h2>
          {right && <div className="card-r">{right}</div>}
        </header>
      )}
      {children}
    </section>
  );
}

export function Page({ title, sub, actions, children }: { title: ReactNode; sub?: ReactNode; actions?: ReactNode; children: ReactNode }) {
  return (
    <div className="page">
      <div className="page-h">
        <div>
          <h1>{title}</h1>
          {sub && <p className="cap">{sub}</p>}
        </div>
        {actions && <div className="actions">{actions}</div>}
      </div>
      {children}
    </div>
  );
}

export function Chip({ tone = '', children }: { tone?: 'ok' | 'warn' | 'bad' | ''; children: ReactNode }) {
  return <span className={`chip ${tone}`}>{children}</span>;
}

const TONE: Record<string, 'ok' | 'warn' | 'bad' | ''> = {
  CONFIRMED: 'ok', OPEN: 'ok', APPROVED: 'ok', COMPLETED: 'ok', SELECTED: 'warn', APPLIED: '', PENDING: 'warn', DRAFT: '', CLOSED: 'warn',
  REJECTED: 'bad', AUTO_REJECTED: 'bad', CANCELED: 'bad', CANCELED_BY_ACTOR: 'bad', CANCELED_BY_COMPANY: 'bad', EXPIRED: 'bad', CANCELED_BY_SYSTEM: 'warn',
};
export const StatusChip = ({ status, label }: { status: string; label: string }) => <Chip tone={TONE[status] ?? ''}>{label}</Chip>;

export function Alert({ tone = 'warn', children }: { tone?: 'warn' | 'bad' | 'ok'; children: ReactNode }) {
  return (
    <div className={`alert ${tone}`} role={tone === 'bad' ? 'alert' : 'status'}>
      {children}
    </div>
  );
}

export function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <label className="fld">
      <span className="fld-l">{label}</span>
      {children}
      {hint && <span className="cap">{hint}</span>}
    </label>
  );
}

export function Button({ variant = 'primary', size = 'md', busy, ...p }: React.ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'line' | 'ghost' | 'bad'; size?: 'md' | 'sm'; busy?: boolean }) {
  return <button {...p} disabled={p.disabled || busy} className={`btn ${variant} ${size} ${p.className ?? ''}`} />;
}

export function Empty({ children }: { children: ReactNode }) {
  return <p className="empty">{children}</p>;
}

export function Loading() {
  return <p className="empty">불러오는 중이에요…</p>;
}

export function Modal({ title, onClose, children, wide }: { title: string; onClose: () => void; children: ReactNode; wide?: boolean }) {
  useEffect(() => {
    const h = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, [onClose]);
  return (
    <div className="scrim" onClick={onClose} role="presentation">
      <div className={`modal ${wide ? 'wide' : ''}`} role="dialog" aria-modal="true" aria-label={title} onClick={(e) => e.stopPropagation()}>
        <header className="card-h">
          <h2>{title}</h2>
          <Button variant="ghost" size="sm" onClick={onClose} aria-label="닫기">
            닫기
          </Button>
        </header>
        {children}
      </div>
    </div>
  );
}

/** 오른쪽에서 열리는 상세 패널. */
export function Drawer({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  useEffect(() => {
    const h = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, [onClose]);
  return (
    <div className="scrim" onClick={onClose} role="presentation">
      <aside className="drawer" role="dialog" aria-modal="true" aria-label={title} onClick={(e) => e.stopPropagation()}>
        <header className="card-h">
          <h2>{title}</h2>
          <Button variant="ghost" size="sm" onClick={onClose}>
            닫기
          </Button>
        </header>
        {children}
      </aside>
    </div>
  );
}

export function Tabs<T extends string>({ value, onChange, items }: { value: T; onChange: (v: T) => void; items: { id: T; label: string; badge?: number }[] }) {
  return (
    <div className="tabs" role="tablist">
      {items.map((i) => (
        <button key={i.id} role="tab" aria-selected={value === i.id} className={value === i.id ? 'on' : ''} onClick={() => onChange(i.id)}>
          {i.label}
          {i.badge ? <b>{i.badge}</b> : null}
        </button>
      ))}
    </div>
  );
}

/** 간단한 알림(토스트). 사용: const toast = useToast(); toast.show('저장했어요'). */
export function useToast() {
  const [msg, setMsg] = useState<{ text: string; bad?: boolean } | null>(null);
  useEffect(() => {
    if (!msg) return;
    const t = setTimeout(() => setMsg(null), 4000);
    return () => clearTimeout(t);
  }, [msg]);
  return {
    show: (text: string) => setMsg({ text }),
    fail: (e: unknown) => setMsg({ text: errorText(e), bad: true }),
    node: msg ? (
      <div className={`toast ${msg.bad ? 'bad' : ''}`} role="status" aria-live="polite">
        {msg.text}
      </div>
    ) : null,
  };
}

export function DataTable({ head, children }: { head: string[]; children: ReactNode }) {
  return (
    <div className="tbl">
      <table>
        <thead>
          <tr>
            {head.map((h) => (
              <th key={h}>{h}</th>
            ))}
          </tr>
        </thead>
        <tbody>{children}</tbody>
      </table>
    </div>
  );
}
