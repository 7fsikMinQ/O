import { useState } from 'react';
import { BrowserRouter, Link, NavLink, Navigate, Outlet, Route, Routes, useLocation } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { api } from './api';
import { SessionProvider, useSession } from './session';
import { Admin } from './pages/Admin';
import { ChangePassword, Forgot, Login, Signup, VerifyEmail, VerifySent } from './pages/Auth';
import { CompanySetup, Members } from './pages/Company';
import { Home } from './pages/Home';
import { JobDetail } from './pages/JobDetail';
import { JobEditor, JobList } from './pages/Jobs';
import { Broadcasts, CancelReviews, Messages, Stars } from './pages/Operations';
import { SchedulePage } from './pages/Schedule';
import { Button, Loading } from './ui';

/** 알림 벨: 안 읽은 수를 20초마다 갱신하고 눌러서 최근 알림을 본다. */
function Bell() {
  const [open, setOpen] = useState(false);
  const count = useQuery({ queryKey: ['unread'], queryFn: () => api.get<{ count: number }>('/notifications/unread-count'), refetchInterval: 20_000 });
  const list = useQuery({ queryKey: ['notifications'], queryFn: () => api.get<{ id: number; title: string; body?: string; readAt?: string }[]>('/notifications?limit=15'), enabled: open });
  return (
    <div className="bell">
      <button className="icon-btn" aria-label={`알림 ${count.data?.count ?? 0}개`} onClick={() => setOpen(!open)}>
        🔔{count.data?.count ? <b>{count.data.count}</b> : null}
      </button>
      {open && (
        <div className="pop" role="dialog" aria-label="알림">
          {list.data?.length === 0 && <p className="empty">알림이 없어요.</p>}
          {list.data?.map((n) => (
            <button key={n.id} className={`note ${n.readAt ? '' : 'new'}`} onClick={() => api.patch(`/notifications/${n.id}/read`).then(() => count.refetch())}>
              <b>{n.title}</b>
              <span className="cap">{n.body}</span>
            </button>
          ))}
          {!!count.data?.count && (
            <Button size="sm" variant="line" onClick={() => api.post('/notifications/read-all').then(() => (count.refetch(), list.refetch()))}>
              모두 읽음
            </Button>
          )}
        </div>
      )}
    </div>
  );
}

function Shell() {
  const { me, companies, company, isAdmin, selectCompany, logout } = useSession();
  const [menu, setMenu] = useState(false);
  const approved = company?.status === 'APPROVED';
  const nav: [string, string][] = isAdmin
    ? [['/admin', '🛡 관리자']]
    : approved
      ? [['/', '🏠 홈'], ['/jobs', '📋 공고'], ['/messages', '💬 메시지'], ['/broadcasts', '📢 단체 알림'], ['/stars', '⭐ 출연자'], ['/reviews', '⚖️ 취소 판정'], ['/members', '👥 직원']]
      : [['/', '🏢 업체 등록']];
  return (
    <div className="app">
      <header className="top">
        <button className="icon-btn menu-btn" aria-label="메뉴" onClick={() => setMenu(!menu)}>
          ☰
        </button>
        <Link to="/" className="brand">
          🎬 보조출연 헬프
        </Link>
        {companies.length > 1 && (
          <select value={company?.id} onChange={(e) => selectCompany(Number(e.target.value))} aria-label="업체 선택">
            {companies.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        )}
        <span className="grow" />
        {!isAdmin && approved && <Bell />}
        <span className="cap who">{me?.name}</span>
        <Button variant="ghost" size="sm" onClick={logout}>
          로그아웃
        </Button>
      </header>
      <div className="body">
        <nav className={`side ${menu ? 'open' : ''}`} onClick={() => setMenu(false)}>
          {nav.map(([to, label]) => (
            <NavLink key={to} to={to} end={to === '/'} className={({ isActive }) => (isActive ? 'on' : '')}>
              {label}
            </NavLink>
          ))}
        </nav>
        <main className="main">
          <Outlet />
        </main>
      </div>
    </div>
  );
}

/** 로그인한 사용자 영역. 업체가 없거나 승인 전이면 등록·대기 화면만 보여 준다. */
function Protected() {
  const { loggedIn, loading, isAdmin, company } = useSession();
  const loc = useLocation();
  if (!loggedIn) return <Navigate to="/login" replace />;
  if (loading) return <Loading />;
  const approved = company?.status === 'APPROVED';
  if (isAdmin && loc.pathname !== '/admin') return <Navigate to="/admin" replace />;
  if (!isAdmin && !approved && loc.pathname !== '/') return <Navigate to="/" replace />;
  return <Shell />;
}

function HomeOrSetup() {
  const { isAdmin, company } = useSession();
  if (isAdmin) return <Navigate to="/admin" replace />;
  return company?.status === 'APPROVED' ? <Home /> : <CompanySetup />;
}

export function App() {
  return (
    <BrowserRouter>
      <SessionProvider>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route path="/signup" element={<Signup />} />
          <Route path="/forgot" element={<Forgot />} />
          <Route path="/verify-email" element={<VerifyEmail />} />
          <Route path="/verify-sent" element={<VerifySent />} />
          <Route path="/change-password" element={<ChangePassword />} />
          <Route element={<Protected />}>
            <Route index element={<HomeOrSetup />} />
            <Route path="jobs" element={<JobList />} />
            <Route path="jobs/new" element={<JobEditor />} />
            <Route path="jobs/:id" element={<JobDetail />} />
            <Route path="jobs/:id/edit" element={<JobEditor />} />
            <Route path="schedules/:id" element={<SchedulePage />} />
            <Route path="messages" element={<Messages />} />
            <Route path="broadcasts" element={<Broadcasts />} />
            <Route path="stars" element={<Stars />} />
            <Route path="reviews" element={<CancelReviews />} />
            <Route path="members" element={<Members />} />
            <Route path="admin" element={<Admin />} />
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </SessionProvider>
    </BrowserRouter>
  );
}
