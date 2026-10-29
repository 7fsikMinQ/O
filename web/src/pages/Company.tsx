import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api';
import { ROLE_LABEL } from '../format';
import { useSession } from '../session';
import { Alert, Button, Card, DataTable, Empty, Field, Loading, Page, StatusChip, useToast, errorText } from '../ui';

/** 업체가 없을 때: 등록 → 사업자등록증 업로드 → 관리자 승인 대기. 승인 전에는 공고·선발 기능이 닫혀 있다. */
export function CompanySetup() {
  const { companies, me } = useSession();
  const qc = useQueryClient();
  const toast = useToast();
  const pending = companies.find((c) => c.status === 'PENDING' || c.status === 'REJECTED');
  const [f, setF] = useState({ name: '', bizRegNo: '', representative: '', phone: '', address: '', introduction: '' });
  const [err, setErr] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);

  const detail = useQuery({ queryKey: ['company', pending?.id], queryFn: () => api.get<Record<string, unknown>>(`/companies/${pending!.id}`), enabled: !!pending });

  async function register(e: FormEvent) {
    e.preventDefault();
    setErr('');
    setBusy(true);
    try {
      const res = await api.post<{ id: number }>('/companies', { ...f, bizRegNo: f.bizRegNo.trim() });
      if (file) {
        const form = new FormData();
        form.append('file', file);
        await api.upload(`/companies/${res.id}/license`, form);
      }
      await qc.invalidateQueries({ queryKey: ['my-companies'] });
      toast.show('등록을 신청했어요. 관리자 승인을 기다려 주세요.');
    } catch (ex) {
      setErr(errorText(ex));
    } finally {
      setBusy(false);
    }
  }

  async function uploadMore(id: number) {
    if (!file) return;
    try {
      const form = new FormData();
      form.append('file', file);
      await api.upload(`/companies/${id}/license`, form);
      toast.show('사업자등록증을 올렸어요.');
      setFile(null);
    } catch (ex) {
      toast.fail(ex);
    }
  }

  const resubmit = useMutation({
    mutationFn: (id: number) => api.post(`/companies/${id}/resubmit`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['my-companies'] }),
    onError: toast.fail,
  });

  if (pending) {
    const rejected = pending.status === 'REJECTED';
    return (
      <Page title={rejected ? '❌ 승인되지 않았어요' : '⏳ 승인을 기다리고 있어요'} sub={`${pending.name} · ${rejected ? '반려' : '검토 중'}`}>
        <div className="stack" style={{ maxWidth: 560 }}>
          <Card title="진행 상황">
            <ol className="steps">
              <li className="done">1 가입</li>
              <li className="done">2 이메일 확인</li>
              <li className="done">3 업체 등록</li>
              <li className={rejected ? 'bad' : 'now'}>4 관리자 승인</li>
            </ol>
            {!rejected && <p className="cap">승인되면 알려 드려요. 승인 전에는 공고를 올릴 수 없어요.</p>}
            {rejected && <Alert tone="bad">{String(detail.data?.rejectReason ?? '서류를 확인해 주세요.')}</Alert>}
          </Card>
          <Card title="사업자등록증">
            <p className="cap">PDF·JPG·PNG, 최대 5MB, 한 번에 3개까지 올릴 수 있어요.</p>
            <input type="file" accept=".pdf,.jpg,.jpeg,.png" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
            <Button variant="line" onClick={() => uploadMore(pending.id)} disabled={!file}>
              올리기
            </Button>
            {rejected && pending.memberRole === 'OWNER' && (
              <Button onClick={() => resubmit.mutate(pending.id)} busy={resubmit.isPending}>
                다시 신청하기
              </Button>
            )}
          </Card>
        </div>
        {toast.node}
      </Page>
    );
  }

  return (
    <Page title="🏢 업체 등록" sub={`${me?.name ?? ''}님, 업체 정보를 적어 주세요. 사업자등록번호와 등록증은 없어도 돼요. 이메일 확인을 마친 분만 등록할 수 있어요.`}>
      <form onSubmit={register} className="stack" style={{ maxWidth: 640 }}>
        <Card>
          <div className="grid2">
            <Field label="상호">
              <input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} required maxLength={100} />
            </Field>
            <Field label="사업자등록번호 (선택)" hint="숫자 10자리, 하이픈 가능. 비워 두어도 돼요">
              <input value={f.bizRegNo} onChange={(e) => setF({ ...f, bizRegNo: e.target.value })} inputMode="numeric" />
            </Field>
            <Field label="대표자">
              <input value={f.representative} onChange={(e) => setF({ ...f, representative: e.target.value })} required maxLength={50} />
            </Field>
            <Field label="전화">
              <input value={f.phone} onChange={(e) => setF({ ...f, phone: e.target.value })} inputMode="tel" />
            </Field>
          </div>
          <Field label="주소">
            <input value={f.address} onChange={(e) => setF({ ...f, address: e.target.value })} maxLength={200} />
          </Field>
          <Field label="소개">
            <textarea value={f.introduction} onChange={(e) => setF({ ...f, introduction: e.target.value })} maxLength={2000} rows={3} />
          </Field>
          <Field label="사업자등록증 (선택 · PDF·JPG·PNG, 5MB 이하)">
            <input type="file" accept=".pdf,.jpg,.jpeg,.png" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
          </Field>
        </Card>
        {err && <Alert tone="bad">{err}</Alert>}
        <Button type="submit" busy={busy}>
          등록 신청
        </Button>
      </form>
      {toast.node}
    </Page>
  );
}

interface Member {
  userId: number;
  name: string;
  email: string;
  memberRole: string;
  status: string;
}

/** 직원 관리: 초대(이메일 확인을 마친 가입자만)·직책 변경·제외. 팀장 지정은 대표만, 스케줄러는 업체당 최대 4명. */
export function Members() {
  const { company } = useSession();
  const qc = useQueryClient();
  const toast = useToast();
  const [email, setEmail] = useState('');
  const [role, setRole] = useState('STAFF');
  const id = company?.id;
  const q = useQuery({ queryKey: ['members', id], queryFn: () => api.get<Member[]>(`/companies/${id}/members`), enabled: !!id });

  const invite = useMutation({
    mutationFn: () => api.post(`/companies/${id}/members`, { email: email.trim(), memberRole: role }),
    onSuccess: () => {
      setEmail('');
      toast.show('초대했어요. 상대가 수락하면 활성화돼요.');
      qc.invalidateQueries({ queryKey: ['members', id] });
    },
    onError: toast.fail,
  });
  const patch = useMutation({
    mutationFn: (v: { userId: number; body: Record<string, string> }) => api.patch(`/companies/${id}/members/${v.userId}`, v.body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['members', id] }),
    onError: toast.fail,
  });

  const schedulers = (q.data ?? []).filter((m) => m.memberRole === 'SCHEDULER' && m.status === 'ACTIVE').length;
  const canManage = company?.memberRole === 'OWNER' || company?.memberRole === 'MANAGER';

  return (
    <Page title="👥 직원 관리" sub={`스케줄러 ${schedulers}/4명 · 대표 1명`}>
      {q.isPending && <Loading />}
      {q.isError && <Alert tone="bad">{errorText(q.error)}</Alert>}
      {q.data && (
        <Card>
          {q.data.length === 0 ? (
            <Empty>아직 직원이 없어요.</Empty>
          ) : (
            <DataTable head={['이름', '이메일', '직책', '상태', '']}>
              {q.data.map((m) => (
                <tr key={m.userId}>
                  <td>{m.name}</td>
                  <td>{m.email}</td>
                  <td>
                    {m.memberRole === 'OWNER' || !canManage ? (
                      ROLE_LABEL[m.memberRole]
                    ) : (
                      <select value={m.memberRole} onChange={(e) => patch.mutate({ userId: m.userId, body: { memberRole: e.target.value } })} aria-label={`${m.name} 직책`}>
                        {['MANAGER', 'SCHEDULER', 'FIELD_LEADER', 'STAFF'].map((r) => (
                          <option key={r} value={r}>
                            {ROLE_LABEL[r]}
                          </option>
                        ))}
                      </select>
                    )}
                  </td>
                  <td>
                    <StatusChip status={m.status === 'ACTIVE' ? 'APPROVED' : 'PENDING'} label={m.status === 'ACTIVE' ? '활동 중' : m.status === 'INVITED' ? '초대함' : m.status} />
                  </td>
                  <td>
                    {m.memberRole !== 'OWNER' && canManage && (
                      <Button variant="line" size="sm" onClick={() => patch.mutate({ userId: m.userId, body: { status: 'REMOVED' } })}>
                        제외
                      </Button>
                    )}
                  </td>
                </tr>
              ))}
            </DataTable>
          )}
        </Card>
      )}
      {canManage && (
        <Card title="직원 초대">
          <form
            className="grid2"
            onSubmit={(e) => {
              e.preventDefault();
              invite.mutate();
            }}
          >
            <Field label="초대할 이메일" hint="이메일 확인을 마친 가입자만 초대할 수 있어요.">
              <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
            </Field>
            <Field label="직책">
              <select value={role} onChange={(e) => setRole(e.target.value)}>
                {['MANAGER', 'SCHEDULER', 'FIELD_LEADER', 'STAFF'].map((r) => (
                  <option key={r} value={r}>
                    {ROLE_LABEL[r]}
                  </option>
                ))}
              </select>
            </Field>
            <div>
              <Button type="submit" busy={invite.isPending}>
                초대하기
              </Button>
            </div>
          </form>
        </Card>
      )}
      <Invitations />
      {toast.node}
    </Page>
  );
}

/** 내가 받은 직원 초대. */
function Invitations() {
  const qc = useQueryClient();
  const toast = useToast();
  const q = useQuery({ queryKey: ['invitations'], queryFn: () => api.get<{ companyId: number; companyName: string; memberRole: string }[]>('/me/company-invitations') });
  const respond = useMutation({
    mutationFn: (v: { id: number; accept: boolean }) => api.post(`/me/company-invitations/${v.id}/${v.accept ? 'accept' : 'decline'}`),
    onSuccess: () => qc.invalidateQueries(),
    onError: toast.fail,
  });
  if (!q.data?.length) return null;
  return (
    <Card title="받은 초대">
      {q.data.map((i) => (
        <div key={i.companyId} className="row">
          <span>
            <b>{i.companyName}</b> · {ROLE_LABEL[i.memberRole]}
          </span>
          <span className="actions">
            <Button size="sm" onClick={() => respond.mutate({ id: i.companyId, accept: true })}>
              수락
            </Button>
            <Button size="sm" variant="line" onClick={() => respond.mutate({ id: i.companyId, accept: false })}>
              거절
            </Button>
          </span>
        </div>
      ))}
      {toast.node}
    </Card>
  );
}
