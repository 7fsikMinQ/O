import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, withCompany } from '../api';
import { GENDER_LABEL, STATUS_LABEL, ageOf, dateOnly, dateTime, kstInputToIso, timeOnly, won } from '../format';
import { useSession } from '../session';
import { Alert, Button, Card, Chip, DataTable, Drawer, Empty, Field, Loading, Modal, Page, StatusChip, Tabs, useToast, errorText } from '../ui';

type J = Record<string, any>; // eslint-disable-line @typescript-eslint/no-explicit-any

interface Applicant {
  id: number;
  status: string;
  appliedAt: string;
  positionId: number;
  positionTitle: string;
  scheduleId: number;
  actorUserId: number;
  applicantName: string;
  applicantPhone: string;
  gender: string;
  birthDate: string;
  heightCm: number;
  weightKg: number;
  confirmedCount: number;
  attendedCount: number;
  noshowCount: number;
  penaltyCount: number;
  withUsCount: number;
  starred: boolean;
  hasRrn: boolean;
  photoUrl?: string;
  conflictOverride: boolean;
}

const GROUPS = [
  { id: 'APPLIED', label: '지원', match: ['APPLIED'] },
  { id: 'SELECTED', label: '합격 통보', match: ['SELECTED'] },
  { id: 'CONFIRMED', label: '확정', match: ['CONFIRMED'] },
  { id: 'REJECTED', label: '불합격', match: ['REJECTED', 'AUTO_REJECTED'] },
  { id: 'OTHER', label: '취소·만료', match: ['CANCELED_BY_ACTOR', 'CANCELED_BY_COMPANY', 'CANCELED_BY_SYSTEM', 'EXPIRED'] },
] as const;

export function JobDetail() {
  const { id } = useParams();
  const nav = useNavigate();
  const qc = useQueryClient();
  const toast = useToast();
  const { company } = useSession();
  const manager = company?.memberRole === 'OWNER' || company?.memberRole === 'MANAGER';
  const planner = manager || company?.memberRole === 'SCHEDULER';
  const [tab, setTab] = useState<(typeof GROUPS)[number]['id']>('APPLIED');
  const [picked, setPicked] = useState<number[]>([]);
  const [open, setOpen] = useState<number | null>(null);
  const [postpone, setPostpone] = useState<number | null>(null);
  const [hours, setHours] = useState('12');

  const job = useQuery({ queryKey: ['job', id], queryFn: () => api.get<J>(`/company/jobs/${id}`) });
  const apps = useQuery({ queryKey: ['job-apps', id], queryFn: () => api.get<Applicant[]>(`/company/jobs/${id}/applications`) });
  const reserve = useQuery({ queryKey: ['reserve', id], queryFn: () => api.get<{ id: number; applicantName: string; positionTitle: string; status: string; remaining: number }[]>(`/company/jobs/${id}/reserve-candidates`), enabled: job.data?.status === 'CLOSED' && planner });

  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['job', id] });
    qc.invalidateQueries({ queryKey: ['job-apps', id] });
    qc.invalidateQueries({ queryKey: ['reserve', id] });
    qc.invalidateQueries({ queryKey: ['jobs'] });
  };
  const act = useMutation({
    mutationFn: (v: { path: string; body?: unknown }) => api.post<J>(v.path, v.body),
    onSuccess: (res, v) => {
      refresh();
      if (v.path.endsWith('/copy') && res?.id) nav(`/jobs/${res.id}`);
      else toast.show('처리했어요.');
    },
    onError: toast.fail,
  });
  const decide = useMutation({
    mutationFn: (v: { ids: number[]; decision: 'SELECT' | 'REJECT' }) => api.post<{ applicationId: number; ok: boolean; reason?: string }[]>(withCompany('/company/applications/decide'), { applicationIds: v.ids, decision: v.decision, responseHours: Number(hours) }),
    onSuccess: (res) => {
      refresh();
      setPicked([]);
      const bad = res.filter((r) => !r.ok);
      if (bad.length) toast.show(`${res.length - bad.length}명 처리, ${bad.length}명은 못 했어요: ${bad[0].reason ?? ''}`);
      else toast.show(`${res.length}명 처리했어요.`);
    },
    onError: toast.fail,
  });
  const headcount = useMutation({
    mutationFn: (v: { id: number; headcount: number }) => api.patch(`/company/positions/${v.id}`, { headcount: v.headcount }),
    onSuccess: refresh,
    onError: toast.fail,
  });

  if (job.isPending) return <Loading />;
  if (job.isError) return <Alert tone="bad">{errorText(job.error)}</Alert>;
  const j = job.data;
  const list = (apps.data ?? []).filter((a) => GROUPS.find((g) => g.id === tab)!.match.includes(a.status as never));
  const count = (gid: string) => (apps.data ?? []).filter((a) => GROUPS.find((g) => g.id === gid)!.match.includes(a.status as never)).length;
  const canDecide = planner && (tab === 'APPLIED' || (tab === 'REJECTED' && j.status === 'CLOSED'));

  return (
    <Page
      title={j.title}
      sub={
        <>
          <StatusChip status={j.status} label={STATUS_LABEL[j.status] ?? j.status} /> {j.applyDeadlineAt ? `지원 마감 ${dateTime(j.applyDeadlineAt)}` : '지원 마감 시각 없음'}
        </>
      }
      actions={
        <>
          {planner && ['DRAFT', 'OPEN'].includes(j.status) && (
            <Link className="btn line md" to={`/jobs/${id}/edit`}>
              ✏️ 고치기
            </Link>
          )}
          {planner && j.status === 'DRAFT' && <Button onClick={() => act.mutate({ path: `/company/jobs/${id}/publish` })}>게시하기</Button>}
          {planner && j.status === 'OPEN' && (
            <Button variant="line" onClick={() => confirm('마감하면 통보하지 않은 지원자는 자동 불합격돼요. 마감할까요?') && act.mutate({ path: `/company/jobs/${id}/close` })}>
              마감하기
            </Button>
          )}
          {planner && j.status === 'CLOSED' && (
            <Button variant="line" onClick={() => act.mutate({ path: `/company/jobs/${id}/reopen` })}>
              마감 해제
            </Button>
          )}
          {planner && (
            <Button variant="line" onClick={() => act.mutate({ path: `/company/jobs/${id}/copy?shiftDays=7` })}>
              복사(1주 뒤)
            </Button>
          )}
          {manager && !['CANCELED', 'COMPLETED'].includes(j.status) && (
            <Button variant="bad" onClick={() => confirm('공고를 취소하면 합격 통보·확정된 분께 취소 알림이 가요(불이익 없음). 취소할까요?') && act.mutate({ path: `/company/jobs/${id}/cancel`, body: { reason: '업체 사정으로 공고 취소' } })}>
              공고 취소
            </Button>
          )}
        </>
      }
    >
      <Alert>{j.timeNotice}</Alert>
      {j.schedules.map((s: J) => (
        <Card
          key={s.id}
          title={`${dateOnly(s.startAt)} ${timeOnly(s.startAt)} ~ ${s.startDate === s.endDate ? '' : `${dateOnly(s.endAt)} `}${timeOnly(s.endAt)} (예상)`}
          right={
            <span className="actions">
              <StatusChip status={s.status === 'POSTPONED' ? 'CANCELED_BY_SYSTEM' : s.status} label={s.status === 'POSTPONED' ? '연기됨' : (STATUS_LABEL[s.status] ?? s.status)} />
              <Link className="btn line sm" to={`/schedules/${s.id}`}>
                인원표·현장
              </Link>
              {planner && ['OPEN', 'CLOSED'].includes(s.status) && j.status !== 'DRAFT' && (
                <Button variant="line" size="sm" onClick={() => setPostpone(s.id)}>
                  연기
                </Button>
              )}
            </span>
          }
        >
          <p className="cap">
            {s.regionName ?? ''} {s.isLodging ? '· 숙박' : ''} {s.meetingPlace ? `· 집합 ${s.meetingPlace}` : ''} {s.shootingLocation ? `· 촬영지 ${s.shootingLocation}` : ''}
          </p>
          <DataTable head={['역할', '조건', '보수', '지원', '통보', '확정', '정원']}>
            {s.positions.map((p: J) => (
              <tr key={p.id}>
                <td>{p.title}</td>
                <td className="cap">
                  {GENDER_LABEL[p.genderReq]} {p.birthYearMin || p.birthYearMax ? `· ${p.birthYearMin ?? ''}~${p.birthYearMax ?? ''}년생` : ''} {p.heightMin || p.heightMax ? `· ${p.heightMin ?? ''}~${p.heightMax ?? ''}cm` : ''}
                </td>
                <td>
                  {won(p.hourlyWageKrw)}/시간 <span className="cap">(예상 {won(p.estimatedPayKrw)})</span>
                </td>
                <td>{p.appliedCount}</td>
                <td>{p.selectedCount}</td>
                <td>{p.confirmedCount}</td>
                <td>
                  {p.headcount}
                  {planner && j.status !== 'DRAFT' && p.status === 'OPEN' && (
                    <span className="stepper">
                      <button aria-label="정원 줄이기" onClick={() => headcount.mutate({ id: p.id, headcount: p.headcount - 1 })} disabled={p.headcount <= 1}>
                        −
                      </button>
                      <button aria-label="정원 늘리기" onClick={() => headcount.mutate({ id: p.id, headcount: p.headcount + 1 })}>
                        ＋
                      </button>
                    </span>
                  )}
                </td>
              </tr>
            ))}
          </DataTable>
        </Card>
      ))}

      {j.status !== 'DRAFT' && (
        <Card title="지원자">
          <Tabs value={tab} onChange={(t) => (setTab(t), setPicked([]))} items={GROUPS.map((g) => ({ id: g.id, label: g.label, badge: count(g.id) }))} />
          {apps.isPending && <Loading />}
          {apps.data && list.length === 0 && <Empty>이 단계의 지원자가 없어요.</Empty>}
          {list.length > 0 && (
            <DataTable head={canDecide ? ['', '사진', '이름', '성별·나이', '키·몸무게', '휴대폰', '역할', '이력', '상태'] : ['사진', '이름', '성별·나이', '키·몸무게', '휴대폰', '역할', '이력', '상태']}>
              {list.map((a) => (
                <tr key={a.id} className="click" onClick={() => setOpen(a.id)}>
                  {canDecide && (
                    <td onClick={(e) => e.stopPropagation()}>
                      <input type="checkbox" aria-label={`${a.applicantName} 선택`} checked={picked.includes(a.id)} onChange={(e) => setPicked(e.target.checked ? [...picked, a.id] : picked.filter((x) => x !== a.id))} />
                    </td>
                  )}
                  <td>{a.photoUrl ? <img className="thumb" src={api.fileUrl(a.photoUrl)} alt="" /> : <span className="thumb ph">🙂</span>}</td>
                  <td>
                    {a.starred && <span title="⭐ 출연자">⭐ </span>}
                    {a.applicantName}
                  </td>
                  <td>
                    {GENDER_LABEL[a.gender]} · {ageOf(a.birthDate)}세
                  </td>
                  <td>
                    {a.heightCm}cm · {a.weightKg}kg
                  </td>
                  <td>{a.applicantPhone}</td>
                  <td>{a.positionTitle}</td>
                  <td className="cap">
                    확정 {a.confirmedCount} · 우리와 {a.withUsCount} · 노쇼 {a.noshowCount}
                    {a.penaltyCount ? ` · 패널티 ${a.penaltyCount}` : ''}
                  </td>
                  <td>
                    <StatusChip status={a.status} label={STATUS_LABEL[a.status] ?? a.status} />
                    {a.conflictOverride && <Chip tone="warn">일정 겹침</Chip>}
                  </td>
                </tr>
              ))}
            </DataTable>
          )}
          {canDecide && (
            <div className="actions bar">
              <span className="cap">{picked.length}명 선택</span>
              <label className="inline">
                <span className="cap">응답 기한</span>
                <select value={hours} onChange={(e) => setHours(e.target.value)} aria-label="응답 기한">
                  {['6', '12', '24', '48'].map((h) => (
                    <option key={h} value={h}>
                      {h}시간
                    </option>
                  ))}
                </select>
              </label>
              <Button disabled={!picked.length} busy={decide.isPending} onClick={() => decide.mutate({ ids: picked, decision: 'SELECT' })}>
                🎉 합격 통보
              </Button>
              {tab === 'APPLIED' && (
                <Button variant="line" disabled={!picked.length} busy={decide.isPending} onClick={() => decide.mutate({ ids: picked, decision: 'REJECT' })}>
                  불합격
                </Button>
              )}
            </div>
          )}
          <p className="cap">합격 통보를 받은 출연자가 확정을 눌러야 정원이 채워져요. 응답 기한이 지나면 자동으로 만료돼요.</p>
        </Card>
      )}

      {j.status === 'CLOSED' && reserve.data && reserve.data.length > 0 && (
        <Card title="마감 후 추가 합격 후보">
          <p className="cap">자리가 남은 역할에, 일정 ±1일에 다른 합격이 없는 지원자예요. 선택해서 합격 통보하세요.</p>
          {reserve.data.map((r) => (
            <div key={r.id} className="row">
              <span>
                {r.applicantName} · {r.positionTitle} <span className="cap">(남은 자리 {r.remaining})</span>
              </span>
              <Button size="sm" onClick={() => decide.mutate({ ids: [r.id], decision: 'SELECT' })}>
                합격 통보
              </Button>
            </div>
          ))}
        </Card>
      )}

      {open !== null && <ApplicantDrawer id={open} manager={manager} planner={planner} onClose={() => setOpen(null)} onChanged={refresh} />}
      {postpone !== null && <PostponeModal scheduleId={postpone} onClose={() => setPostpone(null)} onDone={() => (setPostpone(null), refresh())} />}
      {toast.node}
    </Page>
  );
}

function PostponeModal({ scheduleId, onClose, onDone }: { scheduleId: number; onClose: () => void; onDone: () => void }) {
  const [start, setStart] = useState('');
  const [end, setEnd] = useState('');
  const [err, setErr] = useState('');
  const m = useMutation({
    mutationFn: () => api.post<{ movedApplications: number }>(`/company/schedules/${scheduleId}/postpone`, { newStartAt: kstInputToIso(start), newEndAt: kstInputToIso(end), reason: '일정 연기' }),
    onSuccess: onDone,
    onError: (e) => setErr(errorText(e)),
  });
  return (
    <Modal title="일정 연기" onClose={onClose}>
      <p className="cap">지원자와 합격자가 새 일정으로 자동 이동해요. 합격·확정이었던 분은 합격 통보 상태로 옮겨져 다시 확정해야 하고, 취소해도 불이익이 없어요.</p>
      <div className="grid2">
        <Field label="새 집합 시각 (예상)">
          <input type="datetime-local" value={start} onChange={(e) => setStart(e.target.value)} required />
        </Field>
        <Field label="새 종료 (예상)">
          <input type="datetime-local" value={end} onChange={(e) => setEnd(e.target.value)} required />
        </Field>
      </div>
      {err && <Alert tone="bad">{err}</Alert>}
      <div className="actions">
        <Button variant="line" onClick={onClose}>
          취소
        </Button>
        <Button busy={m.isPending} disabled={!start || !end} onClick={() => m.mutate()}>
          연기하기
        </Button>
      </div>
    </Modal>
  );
}

function ApplicantDrawer({ id, manager, planner, onClose, onChanged }: { id: number; manager: boolean; planner: boolean; onClose: () => void; onChanged: () => void }) {
  const toast = useToast();
  const [rrn, setRrn] = useState('');
  const [msg, setMsg] = useState('');
  const q = useQuery({ queryKey: ['applicant', id], queryFn: () => api.get<J>(`/company/applications/${id}`) });
  const hist = useQuery({ queryKey: ['actor-history', q.data?.actorUserId], queryFn: () => api.get<J>(withCompany(`/company/actors/${q.data!.actorUserId}/history`)), enabled: !!q.data });

  async function reveal() {
    const reason = prompt('주민번호를 전체로 보는 이유를 적어 주세요. (열람 기록이 남아요)');
    if (!reason) return;
    try {
      const r = await api.post<{ rrn: string }>(`/company/applications/${id}/reveal-rrn`, { reason });
      setRrn(r.rrn);
      setTimeout(() => setRrn(''), 30_000);
    } catch (e) {
      toast.fail(e);
    }
  }
  async function cancelApp() {
    if (!confirm('합격을 취소할까요? 출연자에게는 불이익이 없고, 자리는 다시 열려요.')) return;
    try {
      await api.post(`/company/applications/${id}/cancel`, { reason: '업체 사정으로 취소' });
      onChanged();
      onClose();
    } catch (e) {
      toast.fail(e);
    }
  }
  async function send() {
    try {
      await api.post(withCompany('/company/messages'), { actorUserId: q.data!.actorUserId, postingId: q.data!.postingId, subject: '안내', body: msg });
      setMsg('');
      toast.show('메시지를 보냈어요.');
    } catch (e) {
      toast.fail(e);
    }
  }
  async function toggleStar() {
    try {
      if (q.data!.starred) await api.del(withCompany(`/company/starred-actors/${q.data!.actorUserId}`));
      else await api.put(withCompany(`/company/starred-actors/${q.data!.actorUserId}`), {});
      toast.show('⭐ 를 바꿨어요.');
    } catch (e) {
      toast.fail(e);
    }
  }

  const d = q.data;
  const snap = (d?.profileSnapshot ?? {}) as J;
  return (
    <Drawer title="지원자 정보" onClose={onClose}>
      {q.isPending && <Loading />}
      {d && (
        <div className="stack">
          <div className="row">
            <b className="big">{d.applicantName}</b>
            <StatusChip status={d.status} label={STATUS_LABEL[d.status] ?? d.status} />
          </div>
          <div className="photos">
            {d.files?.map((f: J) => (
              <figure key={f.usage}>
                <img src={api.fileUrl(f.url)} alt={f.usage === 'SIGNATURE' ? '서명' : '현재 사진'} />
                <figcaption className="cap">{f.usage === 'SIGNATURE' ? '전자서명' : '현재 사진'}</figcaption>
              </figure>
            ))}
          </div>
          <dl className="dl">
            <dt>휴대폰</dt><dd><a href={`tel:${d.applicantPhone}`}>{d.applicantPhone}</a></dd>
            <dt>지원 역할</dt><dd>{d.positionTitle}</dd>
            <dt>성별·생년월일</dt><dd>{GENDER_LABEL[snap.gender]} · {snap.birth_date}</dd>
            <dt>키·몸무게</dt><dd>{snap.height_cm}cm · {snap.weight_kg}kg</dd>
            <dt>사이즈</dt><dd>상의 {snap.top_size ?? '-'} · 하의 {snap.bottom_size ?? '-'} · 신발 {snap.shoe_size ?? '-'}</dd>
            <dt>머리·문신</dt><dd>{snap.hair_length ?? '-'} · {snap.has_tattoo ? '있음' : '없음'}</dd>
            <dt>탑승 희망지</dt><dd>{d.departureLocation ?? '-'}</dd>
            <dt>주민번호</dt>
            <dd>
              {rrn || d.rrnMasked || '미등록'} {manager && d.rrnMasked && !rrn && <Button variant="line" size="sm" onClick={reveal}>전체 보기</Button>}
            </dd>
            {snap.home_address ? (
              <>
                <dt>집 주소</dt><dd>{snap.home_address} {snap.home_address_detail ?? ''}</dd>
                <dt>계좌</dt><dd>{d.bankName} {d.accountMasked} · {d.accountHolder}</dd>
              </>
            ) : (
              <>
                <dt>집 주소·계좌</dt><dd className="cap">합격 통보 뒤에 볼 수 있어요.</dd>
              </>
            )}
            <dt>지원 메시지</dt><dd>{d.message ?? '-'}</dd>
            <dt>지원 시각</dt><dd>{dateTime(d.appliedAt)} · 서명 약관 v{d.signedTermsVersion}</dd>
            <dt>변조 확인값</dt><dd className="cap mono">{String(d.snapshotHash).slice(0, 16)}…</dd>
          </dl>
          {hist.data && (
            <p className="cap">
              활동 횟수 · 확정 {hist.data.confirmedCount} · 출석 {hist.data.attendedCount} · 노쇼 {hist.data.noshowCount} · 취소 패널티 {hist.data.penaltyCount} (점수는 없어요)
            </p>
          )}
          <Card title="이력">
            {d.history?.map((h: J, i: number) => (
              <p key={i} className="cap">
                {dateTime(h.createdAt)} · {STATUS_LABEL[h.toStatus] ?? h.toStatus} {h.reason ? `(${h.reason})` : ''}
              </p>
            ))}
          </Card>
          {planner && (
            <Card title="메시지 보내기">
              <textarea value={msg} onChange={(e) => setMsg(e.target.value)} rows={2} placeholder="출연자에게 안내를 보내요. 출연자는 답장만 할 수 있어요." aria-label="메시지" />
              <Button size="sm" disabled={!msg.trim()} onClick={send}>
                보내기
              </Button>
            </Card>
          )}
          <div className="actions">
            {manager && d.status === 'CONFIRMED' && (
              <Button variant="line" size="sm" onClick={toggleStar}>
                ⭐ 출연자로 저장/해제
              </Button>
            )}
            {manager && ['SELECTED', 'CONFIRMED'].includes(d.status) && (
              <Button variant="bad" size="sm" onClick={cancelApp}>
                합격 취소
              </Button>
            )}
          </div>
        </div>
      )}
      {toast.node}
    </Drawer>
  );
}
