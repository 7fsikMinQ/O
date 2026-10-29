import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api';
import { STATUS_LABEL, dateTime } from '../format';
import { Alert, Button, Card, Chip, DataTable, Empty, Field, Loading, Modal, Page, StatusChip, Tabs, useToast } from '../ui';

type R = Record<string, any>; // eslint-disable-line @typescript-eslint/no-explicit-any
type Tab = 'companies' | 'users' | 'jobs' | 'reports' | 'settings' | 'flags' | 'audit' | 'stats';

export function Admin() {
  const [tab, setTab] = useState<Tab>('companies');
  return (
    <Page title="🛡 관리자" sub="업체 승인·회원·공고·신고·운영 설정을 다뤄요. 모든 변경은 기록이 남아요.">
      <Tabs
        value={tab}
        onChange={setTab}
        items={[
          { id: 'companies', label: '업체 승인' },
          { id: 'users', label: '회원' },
          { id: 'jobs', label: '공고' },
          { id: 'reports', label: '신고' },
          { id: 'settings', label: '설정·최저시급' },
          { id: 'flags', label: '기능 스위치' },
          { id: 'audit', label: '감사 로그' },
          { id: 'stats', label: '통계' },
        ]}
      />
      {tab === 'companies' && <Companies />}
      {tab === 'users' && <Users />}
      {tab === 'jobs' && <Jobs />}
      {tab === 'reports' && <Reports />}
      {tab === 'settings' && <Settings />}
      {tab === 'flags' && <Flags />}
      {tab === 'audit' && <Audit />}
      {tab === 'stats' && <Stats />}
    </Page>
  );
}

function Companies() {
  const qc = useQueryClient();
  const toast = useToast();
  const [status, setStatus] = useState('PENDING');
  const [open, setOpen] = useState<number | null>(null);
  const [reason, setReason] = useState('');
  const list = useQuery({ queryKey: ['admin-companies', status], queryFn: () => api.get<R[]>(`/admin/companies?status=${status}`) });
  const detail = useQuery({ queryKey: ['admin-company', open], queryFn: () => api.get<R>(`/admin/companies/${open}`), enabled: open !== null });
  const done = () => (setOpen(null), setReason(''), qc.invalidateQueries({ queryKey: ['admin-companies'] }));
  const approve = useMutation({ mutationFn: () => api.post(`/admin/companies/${open}/approve`), onSuccess: () => (toast.show('승인했어요.'), done()), onError: toast.fail });
  const reject = useMutation({ mutationFn: () => api.post(`/admin/companies/${open}/reject`, { reason }), onSuccess: () => (toast.show('반려했어요.'), done()), onError: toast.fail });
  return (
    <>
      <div className="chips">
        {['PENDING', 'APPROVED', 'REJECTED'].map((s) => (
          <button key={s} className={`pill ${status === s ? 'on' : ''}`} onClick={() => setStatus(s)}>{STATUS_LABEL[s] ?? (s === 'REJECTED' ? '반려' : s)}</button>
        ))}
      </div>
      <Card>
        {list.isPending && <Loading />}
        {list.data?.length === 0 && <Empty>해당하는 업체가 없어요.</Empty>}
        {list.data && list.data.length > 0 && (
          <DataTable head={['업체', '대표자', '신청 시각', '상태', '']}>
            {list.data.map((c) => (
              <tr key={c.id}>
                <td>{c.name}</td>
                <td>{c.representative}</td>
                <td>{dateTime(c.submittedAt ?? c.createdAt)}</td>
                <td><StatusChip status={c.status} label={STATUS_LABEL[c.status] ?? c.status} /></td>
                <td><Button variant="line" size="sm" onClick={() => setOpen(c.id)}>검토</Button></td>
              </tr>
            ))}
          </DataTable>
        )}
      </Card>
      {open !== null && (
        <Modal title="업체 신청 검토" onClose={() => setOpen(null)} wide>
          {detail.isPending && <Loading />}
          {detail.data && (
            <div className="stack">
              <dl className="dl">
                <dt>상호</dt><dd>{detail.data.name}</dd>
                <dt>사업자등록번호</dt><dd>{String(detail.data.bizRegNo ?? '') || '입력 안 함'}</dd>
                <dt>대표자</dt><dd>{detail.data.representative}</dd>
                <dt>전화·주소</dt><dd>{detail.data.phone} · {detail.data.address}</dd>
              </dl>
              <div className="photos">
                {(detail.data.licenses as R[]).map((l, i) => (
                  <figure key={i}>
                    {String(l.contentType ?? '').startsWith('image') ? <img src={api.fileUrl(l.url)} alt="사업자등록증" /> : <a href={api.fileUrl(l.url)} target="_blank" rel="noreferrer">📄 PDF 열기</a>}
                    <figcaption className="cap">5분 동안만 열려요 · 열람 기록이 남아요</figcaption>
                  </figure>
                ))}
              </div>
              {detail.data.status === 'PENDING' && (
                <>
                  <Field label="반려 사유 (반려할 때 필수)">
                    <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="예) 등록증이 흐려서 읽을 수 없어요" />
                  </Field>
                  <div className="actions">
                    <Button onClick={() => approve.mutate()} busy={approve.isPending}>승인</Button>
                    <Button variant="bad" disabled={!reason.trim()} onClick={() => reject.mutate()} busy={reject.isPending}>반려</Button>
                  </div>
                </>
              )}
            </div>
          )}
        </Modal>
      )}
      {toast.node}
    </>
  );
}

function Users() {
  const qc = useQueryClient();
  const toast = useToast();
  const [q, setQ] = useState('');
  const list = useQuery({ queryKey: ['admin-users', q], queryFn: () => api.get<R[]>(`/admin/users?q=${encodeURIComponent(q)}`) });
  const suspend = useMutation({
    mutationFn: (v: { id: number; on: boolean }) => (v.on ? api.post(`/admin/users/${v.id}/suspend`, { reason: prompt('정지 사유를 적어 주세요') || '' }) : api.post(`/admin/users/${v.id}/restore`)),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin-users'] }),
    onError: toast.fail,
  });
  return (
    <Card title="회원" right={<input value={q} onChange={(e) => setQ(e.target.value)} placeholder="이름·이메일 검색" aria-label="회원 검색" />}>
      {list.data && (
        <DataTable head={['번호', '이름', '이메일', '휴대폰', '역할', '상태', '']}>
          {list.data.map((u) => (
            <tr key={u.id}>
              <td>{u.id}</td><td>{u.name}</td><td>{u.email}</td><td>{u.phone}</td><td className="cap">{u.roles}</td>
              <td><Chip tone={u.status === 'ACTIVE' ? 'ok' : 'bad'}>{u.status === 'ACTIVE' ? '정상' : '정지'}</Chip></td>
              <td>
                {!String(u.roles ?? '').includes('ADMIN') &&
                  (u.status === 'ACTIVE' ? (
                    <Button variant="line" size="sm" onClick={() => suspend.mutate({ id: u.id, on: true })}>정지</Button>
                  ) : (
                    <Button variant="line" size="sm" onClick={() => suspend.mutate({ id: u.id, on: false })}>해제</Button>
                  ))}
              </td>
            </tr>
          ))}
        </DataTable>
      )}
      {toast.node}
    </Card>
  );
}

function Jobs() {
  const qc = useQueryClient();
  const toast = useToast();
  const list = useQuery({ queryKey: ['admin-jobs'], queryFn: () => api.get<R[]>('/admin/jobs') });
  const hide = useMutation({ mutationFn: (id: number) => api.post(`/admin/jobs/${id}/hide`, { reason: prompt('숨기는 이유') || '' }), onSuccess: () => qc.invalidateQueries({ queryKey: ['admin-jobs'] }), onError: toast.fail });
  return (
    <Card title="공고 모니터링">
      {list.data && (
        <DataTable head={['공고', '업체', '상태', '촬영일', '']}>
          {list.data.map((j) => (
            <tr key={j.id}>
              <td>{j.title}</td><td>{j.companyName}</td><td><StatusChip status={j.status} label={STATUS_LABEL[j.status] ?? j.status} />{j.hidden && <Chip tone="bad">숨김</Chip>}</td><td>{j.firstShootDate}</td>
              <td>{!j.hidden && <Button variant="line" size="sm" onClick={() => hide.mutate(j.id)}>숨기기</Button>}</td>
            </tr>
          ))}
        </DataTable>
      )}
      {toast.node}
    </Card>
  );
}

function Reports() {
  const qc = useQueryClient();
  const toast = useToast();
  const list = useQuery({ queryKey: ['admin-reports'], queryFn: () => api.get<R[]>('/admin/reports') });
  const resolve = useMutation({ mutationFn: (v: { id: number; status: string }) => api.post(`/admin/reports/${v.id}/resolve`, { status: v.status }), onSuccess: () => qc.invalidateQueries({ queryKey: ['admin-reports'] }), onError: toast.fail });
  return (
    <Card title="신고">
      {list.data?.length === 0 && <Empty>접수된 신고가 없어요.</Empty>}
      {list.data && list.data.length > 0 && (
        <DataTable head={['번호', '대상', '사유', '내용', '상태', '']}>
          {list.data.map((r) => (
            <tr key={r.id}>
              <td>{r.id}</td><td>{r.targetType} #{r.targetId}</td><td>{r.reasonCode}</td><td>{r.detail}</td><td>{r.status}</td>
              <td className="actions">
                <Button variant="line" size="sm" onClick={() => resolve.mutate({ id: r.id, status: 'RESOLVED' })}>처리 완료</Button>
                <Button variant="ghost" size="sm" onClick={() => resolve.mutate({ id: r.id, status: 'DISMISSED' })}>기각</Button>
              </td>
            </tr>
          ))}
        </DataTable>
      )}
      {toast.node}
    </Card>
  );
}

function Settings() {
  const qc = useQueryClient();
  const toast = useToast();
  const [year, setYear] = useState(String(new Date().getFullYear() + 1));
  const [wage, setWage] = useState('');
  const list = useQuery({ queryKey: ['admin-settings'], queryFn: () => api.get<R[]>('/admin/settings') });
  const save = useMutation({ mutationFn: (v: { key: string; value: string }) => api.put(`/admin/settings/${v.key}`, { value: v.value }), onSuccess: () => (toast.show('저장했어요.'), qc.invalidateQueries({ queryKey: ['admin-settings'] })), onError: toast.fail });
  const saveWage = useMutation({ mutationFn: () => api.put(`/admin/minimum-wages/${year}`, { hourlyKrw: Number(wage) }), onSuccess: () => toast.show('최저시급을 저장했어요.'), onError: toast.fail });
  return (
    <>
      <Card title="최저시급 (매년 8월 고시 뒤 갱신)">
        <form className="grid2" onSubmit={(e) => (e.preventDefault(), saveWage.mutate())}>
          <Field label="연도"><input type="number" value={year} onChange={(e) => setYear(e.target.value)} required /></Field>
          <Field label="시급 (원)"><input type="number" value={wage} onChange={(e) => setWage(e.target.value)} required /></Field>
          <div><Button type="submit" busy={saveWage.isPending}>저장</Button></div>
        </form>
      </Card>
      <Card title="운영 설정">
        {list.data && (
          <DataTable head={['키', '값', '설명']}>
            {list.data.map((s) => (
              <tr key={s.key}>
                <td className="mono">{s.key}</td>
                <td>
                  <input defaultValue={s.value} aria-label={s.key} onBlur={(e) => e.target.value !== s.value && save.mutate({ key: s.key, value: e.target.value })} />
                </td>
                <td className="cap">{s.description}</td>
              </tr>
            ))}
          </DataTable>
        )}
        <p className="cap">값을 고치고 칸 밖을 누르면 저장돼요.</p>
      </Card>
      {toast.node}
    </>
  );
}

function Flags() {
  const qc = useQueryClient();
  const toast = useToast();
  const list = useQuery({ queryKey: ['admin-flags'], queryFn: () => api.get<R[]>('/admin/feature-flags') });
  const put = useMutation({
    mutationFn: (v: { code: string; enabled: boolean; legal: boolean }) => api.put(`/admin/feature-flags/${v.code}`, { enabled: v.enabled, confirmLegal: v.legal }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin-flags'] }),
    onError: toast.fail,
  });
  return (
    <Card title="기능 스위치">
      <Alert>돈·정산 관련 기능은 법무·세무 확인 뒤에만 켜요. 송금 실행은 만들지 않는 기능이라 켤 수 없어요.</Alert>
      {list.data && (
        <DataTable head={['코드', '설명', '상태', '']}>
          {list.data.map((f) => (
            <tr key={f.code}>
              <td className="mono">{f.code}</td><td className="cap">{f.description}</td>
              <td><Chip tone={f.enabled ? 'ok' : ''}>{f.enabled ? '켜짐' : '꺼짐'}</Chip></td>
              <td>
                <Button
                  variant="line"
                  size="sm"
                  onClick={() => {
                    if (f.enabled) return put.mutate({ code: f.code, enabled: false, legal: false });
                    const legal = !f.needsLegalReview || confirm('법무·세무 확인을 마쳤나요? 확인 뒤에만 켜 주세요.');
                    if (legal) put.mutate({ code: f.code, enabled: true, legal });
                  }}
                >
                  {f.enabled ? '끄기' : '켜기'}
                </Button>
              </td>
            </tr>
          ))}
        </DataTable>
      )}
      {toast.node}
    </Card>
  );
}

function Audit() {
  const list = useQuery({ queryKey: ['admin-audit'], queryFn: () => api.get<R[]>('/admin/audit-logs?limit=100') });
  return (
    <Card title="감사 로그 (최근 100건)">
      {list.data && (
        <DataTable head={['시각', '동작', '대상', '실행자']}>
          {list.data.map((a) => (
            <tr key={a.id}>
              <td>{dateTime(a.createdAt)}</td><td className="mono">{a.action}</td><td>{a.entityType} {a.entityId ?? ''}</td><td>{a.actorUserId ?? '시스템'}</td>
            </tr>
          ))}
        </DataTable>
      )}
    </Card>
  );
}

function Stats() {
  const q = useQuery({ queryKey: ['admin-stats'], queryFn: () => api.get<R>('/admin/stats') });
  const labels: Record<string, string> = { users: '회원', actors: '출연자', companies: '승인된 업체', pendingCompanies: '승인 대기 업체', openPostings: '모집 중 공고', applications: '지원', confirmed: '확정', openReports: '미처리 신고' };
  return (
    <div className="stats">
      {q.data && Object.entries(labels).map(([k, l]) => (
        <Card key={k}>
          <p className="cap">{l}</p>
          <p className="stat">{q.data![k]}</p>
        </Card>
      ))}
    </div>
  );
}
