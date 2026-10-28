import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, currentCompanyId, getTokens, onTokensChange, setCurrentCompanyId, setTokens } from './api';

export interface Me {
  id: number;
  name: string;
  roles: string[];
}
export interface CompanyInfo {
  id: number;
  name: string;
  status: string;
  memberRole: string;
  memberStatus?: string;
}

interface Ctx {
  me?: Me;
  companies: CompanyInfo[];
  company?: CompanyInfo;
  isAdmin: boolean;
  loggedIn: boolean;
  loading: boolean;
  selectCompany: (id: number) => void;
  logout: () => Promise<void>;
}

const SessionContext = createContext<Ctx | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const qc = useQueryClient();
  const [, force] = useState(0);
  useEffect(() => {
    const off = onTokensChange(() => force((n) => n + 1));
    return () => void off();
  }, []);
  const loggedIn = !!getTokens()?.accessToken;
  const limited = loggedIn && getTokens()?.refreshToken == null;   // 임시 비밀번호로 들어온 제한 토큰

  const meQ = useQuery({ queryKey: ['me', loggedIn], queryFn: () => api.get<Me>('/me'), enabled: loggedIn && !limited, retry: false });
  const compQ = useQuery({ queryKey: ['my-companies', loggedIn], queryFn: async () => (await api.get<(CompanyInfo & { companyId: number })[]>('/me/companies')).map((c) => ({ ...c, id: c.companyId })), enabled: loggedIn && !limited, retry: false });

  const companies = (compQ.data ?? []).filter((c) => c.memberStatus === undefined || c.memberStatus === 'ACTIVE');
  const saved = currentCompanyId();
  const company = companies.find((c) => c.id === saved) ?? companies[0];
  useEffect(() => {
    if (company && company.id !== saved) setCurrentCompanyId(company.id);
  }, [company, saved]);

  const value = useMemo<Ctx>(
    () => ({
      me: meQ.data,
      companies,
      company,
      isAdmin: !!meQ.data?.roles.some((r) => r === 'ADMIN' || r === 'SUPER_ADMIN'),
      loggedIn,
      loading: loggedIn && !limited && (meQ.isPending || compQ.isPending),
      selectCompany: (id) => {
        setCurrentCompanyId(id);
        qc.invalidateQueries();
        force((n) => n + 1);
      },
      logout: async () => {
        const t = getTokens();
        try {
          if (t?.refreshToken) await api.post('/auth/logout', { refreshToken: t.refreshToken });
        } catch {
          /* 이미 만료됐어도 로그아웃은 진행 */
        }
        setTokens(null);
        qc.clear();
      },
    }),
    [meQ.data, meQ.isPending, compQ.data, compQ.isPending, loggedIn, company?.id, qc],
  );
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): Ctx {
  const v = useContext(SessionContext);
  if (!v) throw new Error('SessionProvider 안에서만 쓸 수 있어요');
  return v;
}
