import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, withCompany } from '../api';
import { STATUS_LABEL, dateOnly, isoToKstInput, kstInputToIso, won } from '../format';
import { Alert, Button, Card, Chip, DataTable, Empty, Field, Loading, Page, StatusChip, errorText } from '../ui';

interface JobRow {
  id: number;
  title: string;
  status: string;
  firstShootDate?: string;
  lastShootDate?: string;
  applyDeadlineAt?: string;
  headcount: number;
  appliedCount: number;
  selectedCount: number;
  confirmedCount: number;
  waitingCount: number;
}

export function JobList() {
  const [status, setStatus] = useState('');
  const q = useQuery({ queryKey: ['jobs', status], queryFn: () => api.get<JobRow[]>(withCompany(`/company/jobs${status ? `?status=${status}` : ''}`)) });
  return (
    <Page
      title="📋 공고"
      sub="공고를 올리고 지원자를 보고 직접 뽑아요."
      actions={
        <Link className="btn primary md" to="/jobs/new">
          ➕ 새 공고
        </Link>
      }
    >
      <div className="chips">
        {['', 'DRAFT', 'OPEN', 'CLOSED', 'COMPLETED', 'CANCELED'].map((s) => (
          <button key={s} className={`pill ${status === s ? 'on' : ''}`} onClick={() => setStatus(s)}>
            {s ? STATUS_LABEL[s] : '전체'}
          </button>
        ))}
      </div>
      {q.isPending && <Loading />}
      {q.isError && <Alert tone="bad">{errorText(q.error)}</Alert>}
      {q.data && (
        <Card>
          {q.data.length === 0 ? (
            <Empty>공고가 아직 없어요. 새 공고를 만들어 보세요.</Empty>
          ) : (
            <DataTable head={['공고', '상태', '촬영일', '지원', '통보', '확정', '정원']}>
              {q.data.map((j) => (
                <tr key={j.id}>
                  <td>
                    <Link to={`/jobs/${j.id}`}>{j.title}</Link>
                    {j.waitingCount > 0 && <Chip tone="warn">대기 {j.waitingCount}</Chip>}
                  </td>
                  <td>
                    <StatusChip status={j.status} label={STATUS_LABEL[j.status] ?? j.status} />
                  </td>
                  <td>{j.firstShootDate === j.lastShootDate ? dateOnly(j.firstShootDate) : `${dateOnly(j.firstShootDate)} ~ ${dateOnly(j.lastShootDate)}`}</td>
                  <td>{j.appliedCount}</td>
                  <td>{j.selectedCount}</td>
                  <td>{j.confirmedCount}</td>
                  <td>{j.headcount}</td>
                </tr>
              ))}
            </DataTable>
          )}
        </Card>
      )}
    </Page>
  );
}

// ===== 편집기 =====

interface PosForm {
  title: string;
  headcount: number;
  genderReq: string;
  birthYearMin: string;
  birthYearMax: string;
  heightMin: string;
  heightMax: string;
  weightMin: string;
  weightMax: string;
  hairLengths: string[];
  tattooReq: string;
  wageMode: string;
  hourlyWageKrw: string;
  payNote: string;
  dressCode: string;
  requirements: string;
}
interface SchForm {
  startAt: string;
  endAt: string;
  isLodging: boolean;
  regionId: string;
  shootingLocation: string;
  meetingPlace: string;
  meetingAddress: string;
  note: string;
  positions: PosForm[];
}
interface JobForm {
  title: string;
  description: string;
  concealDetails: boolean;
  contactPhone: string;
  applyDeadlineAt: string;
  schedules: SchForm[];
}

const emptyPos = (): PosForm => ({ title: '', headcount: 5, genderReq: 'ANY', birthYearMin: '', birthYearMax: '', heightMin: '', heightMax: '', weightMin: '', weightMax: '', hairLengths: [], tattooReq: 'ANY', wageMode: 'MIN_WAGE', hourlyWageKrw: '', payNote: '', dressCode: '', requirements: '' });
const emptySch = (): SchForm => ({ startAt: '', endAt: '', isLodging: false, regionId: '', shootingLocation: '', meetingPlace: '', meetingAddress: '', note: '', positions: [emptyPos()] });
const num = (v: string) => (v.trim() === '' ? null : Number(v));

function toBody(f: JobForm) {
  return {
    title: f.title.trim(),
    description: f.description || null,
    concealDetails: f.concealDetails,
    contactPhone: f.contactPhone || null,
    applyDeadlineAt: f.applyDeadlineAt ? kstInputToIso(f.applyDeadlineAt) : null,
    schedules: f.schedules.map((s) => ({
      startAt: kstInputToIso(s.startAt),
      endAt: kstInputToIso(s.endAt),
      isLodging: s.isLodging,
      regionId: num(s.regionId),
      shootingLocation: s.shootingLocation || null,
      meetingPlace: s.meetingPlace || null,
      meetingAddress: s.meetingAddress || null,
      note: s.note || null,
      positions: s.positions.map((p) => ({
        title: p.title.trim(),
        headcount: Number(p.headcount),
        genderReq: p.genderReq,
        birthYearMin: num(p.birthYearMin), birthYearMax: num(p.birthYearMax),
        heightMin: num(p.heightMin), heightMax: num(p.heightMax),
        weightMin: num(p.weightMin), weightMax: num(p.weightMax),
        hairLengths: p.hairLengths,
        tattooReq: p.tattooReq,
        wageMode: p.wageMode,
        hourlyWageKrw: p.wageMode === 'CUSTOM' ? num(p.hourlyWageKrw) : null,
        payNote: p.payNote || null,
        dressCode: p.dressCode || null,
        requirements: p.requirements || null,
      })),
    })),
  };
}

type Loaded = Record<string, any>; // eslint-disable-line @typescript-eslint/no-explicit-any

function fromServer(j: Loaded): JobForm {
  const s = (v: unknown) => (v == null ? '' : String(v));
  return {
    title: j.title, description: s(j.description), concealDetails: j.concealDetails, contactPhone: s(j.contactPhone),
    applyDeadlineAt: j.applyDeadlineAt ? isoToKstInput(j.applyDeadlineAt) : '',
    schedules: j.schedules.map((sc: Loaded) => ({
      startAt: isoToKstInput(sc.startAt), endAt: isoToKstInput(sc.endAt), isLodging: sc.isLodging, regionId: s(sc.regionId), shootingLocation: s(sc.shootingLocation),
      meetingPlace: s(sc.meetingPlace), meetingAddress: s(sc.meetingAddress), note: s(sc.note),
      positions: sc.positions.map((p: Loaded) => ({
        title: p.title, headcount: p.headcount, genderReq: p.genderReq, birthYearMin: s(p.birthYearMin), birthYearMax: s(p.birthYearMax), heightMin: s(p.heightMin), heightMax: s(p.heightMax),
        weightMin: s(p.weightMin), weightMax: s(p.weightMax), hairLengths: p.hairLengths ?? [], tattooReq: p.tattooReq, wageMode: p.wageMode, hourlyWageKrw: p.wageMode === 'CUSTOM' ? s(p.hourlyWageKrw) : '',
        payNote: s(p.payNote), dressCode: s(p.dressCode), requirements: s(p.requirements),
      })),
    })),
  };
}

const HAIR = [['SHAVED', '삭발'], ['SHORT', '짧게'], ['MEDIUM', '중간'], ['LONG', '길게']];

export function JobEditor() {
  const { id } = useParams();
  const nav = useNavigate();
  const qc = useQueryClient();
  const editing = id !== undefined;
  const [form, setForm] = useState<JobForm | null>(editing ? null : { title: '', description: '', concealDetails: true, contactPhone: '', applyDeadlineAt: '', schedules: [emptySch()] });
  const [err, setErr] = useState('');
  const loaded = useQuery({
    queryKey: ['job-edit', id],
    queryFn: async () => {
      const j = await api.get<Loaded>(`/company/jobs/${id}`);
      setForm(fromServer(j));
      return j;
    },
    enabled: editing,
    staleTime: 0,
  });
  const wage = useQuery({ queryKey: ['min-wage'], queryFn: () => api.get<{ year: number; hourlyKrw: number }>('/master/minimum-wage') });
  const regions = useQuery({ queryKey: ['regions'], queryFn: () => api.get<{ id: number; name: string }[]>('/master/regions') });

  const save = useMutation({
    mutationFn: async (publish: boolean) => {
      const body = toBody(form!);
      const job = editing ? await api.put<Loaded>(`/company/jobs/${id}`, body) : await api.post<Loaded>(withCompany('/company/jobs'), body);
      if (publish) await api.post(`/company/jobs/${job.id}/publish`);
      return job;
    },
    onSuccess: (job) => {
      qc.invalidateQueries({ queryKey: ['jobs'] });
      nav(`/jobs/${job.id}`);
    },
    onError: (e) => setErr(errorText(e)),
  });

  if (editing && loaded.isPending) return <Loading />;
  if (!form) return <Loading />;
  const locked = editing && loaded.data?.status !== 'DRAFT';
  const set = (patch: Partial<JobForm>) => setForm({ ...form, ...patch });
  const setSch = (i: number, patch: Partial<SchForm>) => set({ schedules: form.schedules.map((s, k) => (k === i ? { ...s, ...patch } : s)) });
  const setPos = (i: number, j: number, patch: Partial<PosForm>) => setSch(i, { positions: form.schedules[i].positions.map((p, k) => (k === j ? { ...p, ...patch } : p)) });

  return (
    <Page title={editing ? '✏️ 공고 고치기' : '➕ 새 공고'} sub="시간은 예상이에요. 집합 ±2시간, 종료 ±4시간 변동·연장될 수 있다는 안내가 출연자에게 항상 보여요.">
      {locked && <Alert>게시된 공고는 제목·설명·연락처·지원 마감만 고칠 수 있어요. 일정·역할은 그대로 유지돼요.</Alert>}
      <form
        className="stack"
        onSubmit={(e) => {
          e.preventDefault();
          setErr('');
          save.mutate(false);
        }}
      >
        <Card title="공고 정보">
          <Field label="제목 (가제로 써도 돼요)">
            <input value={form.title} onChange={(e) => set({ title: e.target.value })} required maxLength={200} />
          </Field>
          <Field label="설명">
            <textarea value={form.description} onChange={(e) => set({ description: e.target.value })} rows={3} />
          </Field>
          <div className="grid2">
            <Field label="현장 연락처" hint="합격한 분에게만 보여요.">
              <input value={form.contactPhone} onChange={(e) => set({ contactPhone: e.target.value })} inputMode="tel" />
            </Field>
            <Field label="지원 마감 (비우면 마감을 직접 눌러요)">
              <input type="datetime-local" value={form.applyDeadlineAt} onChange={(e) => set({ applyDeadlineAt: e.target.value })} />
            </Field>
          </div>
          <label className="check">
            <input type="checkbox" checked={form.concealDetails} onChange={(e) => set({ concealDetails: e.target.checked })} />
            <span>비공개 현장 — 촬영지·집합지는 합격 통보를 받은 분에게만 보여요</span>
          </label>
        </Card>

        {form.schedules.map((s, i) => (
          <Card
            key={i}
            title={`일정 ${i + 1}`}
            right={
              !locked && form.schedules.length > 1 ? (
                <Button type="button" variant="ghost" size="sm" onClick={() => set({ schedules: form.schedules.filter((_, k) => k !== i) })}>
                  일정 삭제
                </Button>
              ) : null
            }
          >
            <fieldset disabled={locked} className="stack">
              <div className="grid2">
                <Field label="집합 시각 (예상)">
                  <input type="datetime-local" value={s.startAt} onChange={(e) => setSch(i, { startAt: e.target.value })} required />
                </Field>
                <Field label="종료 (예상)" hint="1시간 ~ 21일(3주)">
                  <input type="datetime-local" value={s.endAt} onChange={(e) => setSch(i, { endAt: e.target.value })} required />
                </Field>
                <Field label="지역">
                  <select value={s.regionId} onChange={(e) => setSch(i, { regionId: e.target.value })}>
                    <option value="">선택 안 함</option>
                    {regions.data?.map((r) => (
                      <option key={r.id} value={r.id}>
                        {r.name}
                      </option>
                    ))}
                  </select>
                </Field>
                <label className="check" style={{ alignSelf: 'end' }}>
                  <input type="checkbox" checked={s.isLodging} onChange={(e) => setSch(i, { isLodging: e.target.checked })} />
                  <span>숙박 일정</span>
                </label>
                <Field label="촬영지 (비공개)">
                  <input value={s.shootingLocation} onChange={(e) => setSch(i, { shootingLocation: e.target.value })} />
                </Field>
                <Field label="집합 장소">
                  <input value={s.meetingPlace} onChange={(e) => setSch(i, { meetingPlace: e.target.value })} />
                </Field>
              </div>
              <Field label="집합 주소 (지도 열기에 쓰여요)">
                <input value={s.meetingAddress} onChange={(e) => setSch(i, { meetingAddress: e.target.value })} />
              </Field>
              <Field label="메모">
                <input value={s.note} onChange={(e) => setSch(i, { note: e.target.value })} />
              </Field>

              {s.positions.map((p, j) => (
                <div key={j} className="sub-card">
                  <div className="row">
                    <b>역할 {j + 1}</b>
                    {s.positions.length > 1 && (
                      <Button type="button" variant="ghost" size="sm" onClick={() => setSch(i, { positions: s.positions.filter((_, k) => k !== j) })}>
                        삭제
                      </Button>
                    )}
                  </div>
                  <div className="grid2">
                    <Field label="역할 이름">
                      <input value={p.title} onChange={(e) => setPos(i, j, { title: e.target.value })} required maxLength={100} placeholder="예) 클럽 20대 남녀" />
                    </Field>
                    <Field label="모집 인원">
                      <input type="number" min={1} max={1000} value={p.headcount} onChange={(e) => setPos(i, j, { headcount: Number(e.target.value) })} required />
                    </Field>
                    <Field label="성별">
                      <select value={p.genderReq} onChange={(e) => setPos(i, j, { genderReq: e.target.value })}>
                        <option value="ANY">무관</option>
                        <option value="MALE">남</option>
                        <option value="FEMALE">여</option>
                      </select>
                    </Field>
                    <Field label="문신">
                      <select value={p.tattooReq} onChange={(e) => setPos(i, j, { tattooReq: e.target.value })}>
                        <option value="ANY">무관</option>
                        <option value="NONE">없는 분만</option>
                      </select>
                    </Field>
                    <Field label="출생연도 (이상~이하)">
                      <span className="range">
                        <input type="number" value={p.birthYearMin} onChange={(e) => setPos(i, j, { birthYearMin: e.target.value })} aria-label="출생연도 이상" />
                        <i>~</i>
                        <input type="number" value={p.birthYearMax} onChange={(e) => setPos(i, j, { birthYearMax: e.target.value })} aria-label="출생연도 이하" />
                      </span>
                    </Field>
                    <Field label="키 cm (이상~이하)">
                      <span className="range">
                        <input type="number" value={p.heightMin} onChange={(e) => setPos(i, j, { heightMin: e.target.value })} aria-label="키 이상" />
                        <i>~</i>
                        <input type="number" value={p.heightMax} onChange={(e) => setPos(i, j, { heightMax: e.target.value })} aria-label="키 이하" />
                      </span>
                    </Field>
                    <Field label="몸무게 kg (이상~이하)">
                      <span className="range">
                        <input type="number" value={p.weightMin} onChange={(e) => setPos(i, j, { weightMin: e.target.value })} aria-label="몸무게 이상" />
                        <i>~</i>
                        <input type="number" value={p.weightMax} onChange={(e) => setPos(i, j, { weightMax: e.target.value })} aria-label="몸무게 이하" />
                      </span>
                    </Field>
                    <Field label="머리 길이 (고르면 그 길이만)">
                      <span className="checks">
                        {HAIR.map(([v, l]) => (
                          <label key={v} className="check">
                            <input type="checkbox" checked={p.hairLengths.includes(v)} onChange={(e) => setPos(i, j, { hairLengths: e.target.checked ? [...p.hairLengths, v] : p.hairLengths.filter((x) => x !== v) })} />
                            <span>{l}</span>
                          </label>
                        ))}
                      </span>
                    </Field>
                  </div>
                  <div className="grid2">
                    <Field label="보수 (시급제)" hint={`최저시급 선택 시 근무 시작일 연도 값(${wage.data?.year}년 ${won(wage.data?.hourlyKrw)}) · 9시간 이내는 시급×8시간`}>
                      <select value={p.wageMode} onChange={(e) => setPos(i, j, { wageMode: e.target.value })}>
                        <option value="MIN_WAGE">최저시급</option>
                        <option value="CUSTOM">직접 입력</option>
                      </select>
                    </Field>
                    {p.wageMode === 'CUSTOM' && (
                      <Field label="시급 (원)" hint="최저시급 이상으로 입력해 주세요.">
                        <input type="number" value={p.hourlyWageKrw} onChange={(e) => setPos(i, j, { hourlyWageKrw: e.target.value })} required />
                      </Field>
                    )}
                  </div>
                  <Field label="식대·교통 등 보수 비고">
                    <input value={p.payNote} onChange={(e) => setPos(i, j, { payNote: e.target.value })} />
                  </Field>
                  <div className="grid2">
                    <Field label="의상">
                      <input value={p.dressCode} onChange={(e) => setPos(i, j, { dressCode: e.target.value })} />
                    </Field>
                    <Field label="요청 사항">
                      <input value={p.requirements} onChange={(e) => setPos(i, j, { requirements: e.target.value })} />
                    </Field>
                  </div>
                </div>
              ))}
              {!locked && (
                <Button type="button" variant="line" size="sm" onClick={() => setSch(i, { positions: [...s.positions, emptyPos()] })}>
                  ➕ 역할 추가
                </Button>
              )}
            </fieldset>
          </Card>
        ))}
        {!locked && (
          <Button type="button" variant="line" onClick={() => set({ schedules: [...form.schedules, emptySch()] })}>
            ➕ 일정 추가
          </Button>
        )}
        {err && <Alert tone="bad">{err}</Alert>}
        <div className="actions">
          <Button type="submit" variant="line" busy={save.isPending}>
            {editing ? '저장' : '임시저장'}
          </Button>
          {!locked && (
            <Button type="button" busy={save.isPending} onClick={() => (setErr(''), save.mutate(true))}>
              게시하기
            </Button>
          )}
        </div>
      </form>
    </Page>
  );
}

export { won };
