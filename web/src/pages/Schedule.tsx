import { useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api';
import { GENDER_LABEL, dateOnly, dateTime, timeOnly } from '../format';
import { useSession } from '../session';
import { Alert, Button, Card, Chip, DataTable, Empty, Loading, Page, useToast, errorText } from '../ui';

type R = Record<string, any>; // eslint-disable-line @typescript-eslint/no-explicit-any

const RESULT_LABEL: Record<string, string> = { PENDING: '대기', ATTENDED: '출석', LATE: '지각', EARLY_LEAVE: '조퇴', NO_SHOW: '노쇼', EXCUSED: '사유 인정' };

/** 일정 단위 현장 화면: 인원표 · 인원 정보표(마스킹) · 출퇴근 세션 열기/QR 스캔 · 참석 미응답자 · 수기 출석 · 암호화 엑셀. */
export function SchedulePage() {
  const { id } = useParams();
  const { company } = useSession();
  const qc = useQueryClient();
  const toast = useToast();
  const role = company?.memberRole ?? '';
  const field = ['OWNER', 'MANAGER', 'SCHEDULER', 'FIELD_LEADER'].includes(role);
  const manager = role === 'OWNER' || role === 'MANAGER';
  const [scanResult, setScanResult] = useState<R | null>(null);

  const head = useQuery({ queryKey: ['headcount', id], queryFn: () => api.get<R[]>(`/company/schedules/${id}/headcount`) });
  const roster = useQuery({ queryKey: ['roster', id], queryFn: () => api.get<R[]>(`/company/schedules/${id}/roster`), enabled: field });
  const pending = useQuery({ queryKey: ['departure', id], queryFn: () => api.get<R[]>(`/company/schedules/${id}/departure-pending`), enabled: field });
  const sessions = useQuery({ queryKey: ['sessions', id], queryFn: () => api.get<R[]>(`/company/schedules/${id}/attendance-sessions`) });
  const refresh = () => qc.invalidateQueries();

  const open = useMutation({
    mutationFn: (kind: 'CHECKIN' | 'CHECKOUT') => api.post(`/company/schedules/${id}/attendance-sessions`, { kind, workDate: new Date(Date.now() + 9 * 3600e3).toISOString().slice(0, 10) }),
    onSuccess: () => (toast.show('열었어요. 출연자 앱에 QR 이 나타나요.'), refresh()),
    onError: toast.fail,
  });
  const close = useMutation({ mutationFn: (sid: number) => api.post(`/company/attendance-sessions/${sid}/close`), onSuccess: refresh, onError: toast.fail });
  const manual = useMutation({
    mutationFn: (v: { applicationId: number; result: string }) => api.put(`/company/schedules/${id}/attendances`, [{ applicationId: v.applicationId, workDate: new Date(Date.now() + 9 * 3600e3).toISOString().slice(0, 10), result: v.result }]),
    onSuccess: () => (toast.show('저장했어요.'), refresh()),
    onError: toast.fail,
  });

  async function download() {
    const pw = prompt('엑셀 파일을 열 때 쓸 비밀번호를 정해 주세요. (6자 이상)\n주민번호·계좌 전체가 들어 있어서 암호화해서 내려받아요.');
    if (!pw) return;
    try {
      const blob = await api.blob(`/company/schedules/${id}/roster.xlsx?password=${encodeURIComponent(pw)}`);
      const a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      a.download = '인원정보표.xlsx';
      a.click();
      URL.revokeObjectURL(a.href);
    } catch (e) {
      toast.fail(e);
    }
  }

  const openSessions = (sessions.data ?? []).filter((s) => !s.closedAt);

  return (
    <Page title="🧑‍🤝‍🧑 인원표 · 현장" sub="합격 통보·확정 인원을 확인하고 출퇴근을 관리해요." actions={<Link className="btn line md" to="/jobs">공고 목록</Link>}>
      <Card title="인원표">
        {head.isPending && <Loading />}
        {head.data && (
          <DataTable head={['역할', '정원', '지원', '통보', '확정', '참석 확인', '출석', '노쇼']}>
            {head.data.map((h) => (
              <tr key={h.positionId}>
                <td>{h.positionTitle}</td>
                <td>{h.headcount}</td>
                <td>{h.appliedCount}</td>
                <td>{h.selectedCount}</td>
                <td>{h.confirmedCount}</td>
                <td>{h.departureConfirmedCount}</td>
                <td>{h.attendedCount}</td>
                <td>{h.noshowCount}</td>
              </tr>
            ))}
          </DataTable>
        )}
      </Card>

      <Card title="📲 출퇴근 (QR)" right={<span className="cap">업체 구성원이면 누구나 열 수 있어요</span>}>
        <div className="actions">
          <Button onClick={() => open.mutate('CHECKIN')} busy={open.isPending}>출근 열기</Button>
          <Button variant="line" onClick={() => open.mutate('CHECKOUT')} busy={open.isPending}>퇴근 열기</Button>
        </div>
        {openSessions.map((s) => (
          <div key={s.id} className="row">
            <span>
              <Chip tone="ok">{s.kind === 'CHECKIN' ? '출근' : '퇴근'} 열림</Chip> <span className="cap">{dateOnly(s.workDate)} · {timeOnly(s.openedAt)}부터</span>
            </span>
            <Button variant="line" size="sm" onClick={() => close.mutate(s.id)}>닫기</Button>
          </div>
        ))}
        <QrScanner onResult={(r) => (setScanResult(r), refresh())} onError={toast.fail} />
        {scanResult && (
          <Alert tone="ok">
            {scanResult.name} · {scanResult.position} — {scanResult.alreadyRecorded ? '이미 기록됐어요' : scanResult.kind === 'CHECKIN' ? `출근 ${RESULT_LABEL[scanResult.result] ?? ''}` : `퇴근 (${scanResult.workedMinutes}분 근무)`}
          </Alert>
        )}
      </Card>

      {field && (
        <Card
          title="인원 정보표"
          right={
            manager ? (
              <Button variant="line" size="sm" onClick={download}>
                엑셀 받기 (비밀번호 암호화)
              </Button>
            ) : null
          }
        >
          {roster.isPending && <Loading />}
          {roster.isError && <Alert tone="bad">{errorText(roster.error)}</Alert>}
          {roster.data && roster.data.length === 0 && <Empty>아직 합격 통보를 받은 분이 없어요.</Empty>}
          {roster.data && roster.data.length > 0 && (
            <DataTable head={['이름', '역할', '휴대폰', '성별·생년', '사이즈', '주민번호', '계좌', '집 주소', '상태', '출석']}>
              {roster.data.map((r) => (
                <tr key={r.applicationId}>
                  <td>{r.starred && '⭐ '}{r.name}</td>
                  <td>{r.positionTitle}</td>
                  <td>{r.phone}</td>
                  <td>{GENDER_LABEL[r.gender]} · {r.birthDate}</td>
                  <td className="cap">{r.heightCm}/{r.weightKg} · {r.topSize ?? '-'}/{r.bottomSize ?? '-'}/{r.shoeSize ?? '-'}</td>
                  <td className="mono">{r.rrnMasked ?? '-'}</td>
                  <td>{r.bankName} {r.accountMasked}</td>
                  <td>{r.homeAddress}</td>
                  <td>
                    <Chip tone={r.status === 'CONFIRMED' ? 'ok' : 'warn'}>{r.status === 'CONFIRMED' ? '확정' : '통보'}</Chip>
                    {r.departureConfirmedAt && <Chip tone="ok">참석 확인</Chip>}
                  </td>
                  <td>
                    {r.status === 'CONFIRMED' && (
                      <select value={r.attendanceResult ?? 'PENDING'} onChange={(e) => manual.mutate({ applicationId: r.applicationId, result: e.target.value })} aria-label={`${r.name} 출석`}>
                        {Object.entries(RESULT_LABEL).map(([v, l]) => (
                          <option key={v} value={v}>{l}</option>
                        ))}
                      </select>
                    )}
                    {r.checkinAt && <span className="cap"> {timeOnly(r.checkinAt)}{r.checkoutAt ? `~${timeOnly(r.checkoutAt)}` : ''}</span>}
                  </td>
                </tr>
              ))}
            </DataTable>
          )}
          <p className="cap">주민번호는 가려서 보여요. 전체는 대표·팀장이 사유를 적고 지원자 화면에서 열람하거나 암호화 엑셀로 받아요. 열람·내려받기는 기록이 남아요.</p>
        </Card>
      )}

      {field && pending.data && pending.data.length > 0 && (
        <Card title="참석 확인을 아직 안 한 분">
          {pending.data.map((p) => (
            <div key={p.applicationId} className="row">
              <span>{p.name}</span>
              <a href={`tel:${p.phone}`}>{p.phone}</a>
            </div>
          ))}
        </Card>
      )}
      {toast.node}
    </Page>
  );
}

/** 카메라로 QR 을 읽는다(브라우저가 지원할 때). 안 되면 토큰을 직접 붙여 넣는다. */
function QrScanner({ onResult, onError }: { onResult: (r: R) => void; onError: (e: unknown) => void }) {
  const video = useRef<HTMLVideoElement>(null);
  const [on, setOn] = useState(false);
  const [manual, setManual] = useState('');
  const supported = typeof window !== 'undefined' && 'BarcodeDetector' in window;
  const last = useRef('');

  async function submit(token: string) {
    if (!token || token === last.current) return;
    last.current = token;
    try {
      onResult(await api.post<R>('/company/attendance/scan', { token }));
    } catch (e) {
      onError(e);
    } finally {
      setTimeout(() => (last.current = ''), 3000);
    }
  }

  useEffect(() => {
    if (!on || !supported) return;
    let stop = false;
    let stream: MediaStream | null = null;
    (async () => {
      try {
        stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } });
        if (video.current) {
          video.current.srcObject = stream;
          await video.current.play();
        }
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        const detector = new (window as any).BarcodeDetector({ formats: ['qr_code'] });
        while (!stop) {
          const codes = video.current ? await detector.detect(video.current) : [];
          if (codes[0]?.rawValue) await submit(codes[0].rawValue);
          await new Promise((r) => setTimeout(r, 400));
        }
      } catch (e) {
        onError(e);
        setOn(false);
      }
    })();
    return () => {
      stop = true;
      stream?.getTracks().forEach((t) => t.stop());
    };
  }, [on]);

  return (
    <div className="stack">
      {supported && (
        <Button variant="line" onClick={() => setOn(!on)}>
          {on ? '카메라 끄기' : '📷 QR 스캔하기'}
        </Button>
      )}
      {on && <video ref={video} className="cam" muted playsInline aria-label="QR 스캔 화면" />}
      <form
        className="row"
        onSubmit={(e) => {
          e.preventDefault();
          submit(manual.trim());
          setManual('');
        }}
      >
        <input value={manual} onChange={(e) => setManual(e.target.value)} placeholder="QR 이 안 읽히면 코드를 붙여 넣어요" aria-label="QR 코드 직접 입력" />
        <Button type="submit" size="sm" variant="line" disabled={!manual.trim()}>
          기록
        </Button>
      </form>
      <p className="cap">출연자 앱에 나타난 QR 은 30초마다 바뀌어요. 한 번 읽으면 같은 QR 을 다시 읽어도 한 번만 기록돼요. {dateTime(new Date().toISOString())} 기준.</p>
    </div>
  );
}
