import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, withCompany } from '../api';
import { dateTime } from '../format';
import { useSession } from '../session';
import { Alert, Button, Card, Chip, DataTable, Empty, Field, Loading, Page, Tabs, useToast, errorText } from '../ui';

type R = Record<string, any>; // eslint-disable-line @typescript-eslint/no-explicit-any

/** 메시지함: 업체가 시작하고 출연자는 답장만 한다. */
export function Messages() {
  const qc = useQueryClient();
  const toast = useToast();
  const [sel, setSel] = useState<number | null>(null);
  const [text, setText] = useState('');
  const threads = useQuery({ queryKey: ['threads'], queryFn: () => api.get<R[]>(withCompany('/company/messages/threads')), refetchInterval: 20_000 });
  const thread = useQuery({ queryKey: ['thread', sel], queryFn: () => api.get<R>(withCompany(`/company/messages/threads/${sel}`)), enabled: sel !== null, refetchInterval: 15_000 });
  const reply = useMutation({
    mutationFn: () => api.post(withCompany(`/company/messages/threads/${sel}/reply`), { body: text }),
    onSuccess: () => (setText(''), qc.invalidateQueries({ queryKey: ['thread', sel] }), qc.invalidateQueries({ queryKey: ['threads'] })),
    onError: toast.fail,
  });
  return (
    <Page title="💬 메시지" sub="지원한 출연자에게 안내를 보내요. 출연자는 답장만 할 수 있어요.">
      <div className="split">
        <Card title="대화">
          {threads.isPending && <Loading />}
          {threads.data?.length === 0 && <Empty>대화가 없어요. 지원자 화면에서 메시지를 보낼 수 있어요.</Empty>}
          {threads.data?.map((t) => (
            <button key={t.id} className={`thread ${sel === t.id ? 'on' : ''}`} onClick={() => setSel(t.id)}>
              <span className="row">
                <b>{t.actorName}</b>
                {t.unread > 0 && <Chip tone="warn">새 답장 {t.unread}</Chip>}
              </span>
              <span className="cap">{t.subject ?? ''} · {t.lastBody}</span>
            </button>
          ))}
        </Card>
        <Card title={thread.data ? `${thread.data.actorName} · ${thread.data.subject ?? ''}` : '대화를 골라 주세요'}>
          {thread.data && (
            <div className="stack">
              <div className="bubbles">
                {(thread.data.messages as R[]).map((m) => (
                  <div key={m.id} className={`bubble ${m.senderSide === 'COMPANY' ? 'me' : ''}`}>
                    <p>{m.body}</p>
                    <span className="cap">{dateTime(m.createdAt)}</span>
                  </div>
                ))}
              </div>
              <textarea value={text} onChange={(e) => setText(e.target.value)} rows={2} aria-label="답장" placeholder="답장을 써 주세요" />
              <Button disabled={!text.trim()} busy={reply.isPending} onClick={() => reply.mutate()}>
                보내기
              </Button>
            </div>
          )}
        </Card>
      </div>
      {toast.node}
    </Page>
  );
}

/** 단체 알림(공지)과 읽음 현황·미읽음 재안내. */
export function Broadcasts() {
  const qc = useQueryClient();
  const toast = useToast();
  const { company } = useSession();
  const manager = company?.memberRole === 'OWNER' || company?.memberRole === 'MANAGER';
  const [f, setF] = useState({ postingId: '', target: 'ALL_CONFIRMED', title: '', body: '' });
  const [view, setView] = useState<number | null>(null);
  const jobs = useQuery({ queryKey: ['jobs', ''], queryFn: () => api.get<R[]>(withCompany('/company/jobs')) });
  const list = useQuery({ queryKey: ['broadcasts'], queryFn: () => api.get<R[]>(withCompany('/company/broadcasts')) });
  const receipts = useQuery({ queryKey: ['receipts', view], queryFn: () => api.get<R>(`/company/broadcasts/${view}/receipts`), enabled: view !== null && manager });
  const send = useMutation({
    mutationFn: () => api.post<R>(withCompany('/company/broadcasts'), { postingId: Number(f.postingId), target: f.target, title: f.title, body: f.body }),
    onSuccess: (r) => (toast.show(`${r.recipientCount}명에게 보냈어요.`), setF({ ...f, title: '', body: '' }), qc.invalidateQueries({ queryKey: ['broadcasts'] })),
    onError: toast.fail,
  });
  const remind = useMutation({
    mutationFn: (id: number) => api.post<R>(`/company/broadcasts/${id}/remind-unread`),
    onSuccess: (r) => (toast.show(`안 읽은 ${r.reminded}명에게 다시 알렸어요. (사람당 최대 5번)`), qc.invalidateQueries({ queryKey: ['receipts', view] })),
    onError: toast.fail,
  });
  return (
    <Page title="📢 단체 알림" sub="합격자 전체·참석 미확인자 등에게 한 번에 보내고, 누가 읽었는지 확인해요.">
      <Card title="새 알림 보내기">
        <form className="stack" onSubmit={(e) => (e.preventDefault(), send.mutate())}>
          <div className="grid2">
            <Field label="공고">
              <select value={f.postingId} onChange={(e) => setF({ ...f, postingId: e.target.value })} required>
                <option value="">고르세요</option>
                {jobs.data?.filter((j) => j.status !== 'DRAFT').map((j) => (
                  <option key={j.id} value={j.id}>{j.title}</option>
                ))}
              </select>
            </Field>
            <Field label="받는 사람">
              <select value={f.target} onChange={(e) => setF({ ...f, target: e.target.value })}>
                <option value="ALL_CONFIRMED">확정한 사람 전체</option>
                <option value="ALL_SELECTED">합격 통보한 사람(확정 전)</option>
                <option value="DEPARTURE_UNCONFIRMED">참석 확인 안 한 사람</option>
                <option value="CLOSED_AVAILABLE">마감 후 가능한 지원자</option>
              </select>
            </Field>
          </div>
          <Field label="제목">
            <input value={f.title} onChange={(e) => setF({ ...f, title: e.target.value })} required maxLength={200} />
          </Field>
          <Field label="내용">
            <textarea value={f.body} onChange={(e) => setF({ ...f, body: e.target.value })} rows={3} required maxLength={2000} />
          </Field>
          <Button type="submit" busy={send.isPending}>보내기</Button>
        </form>
      </Card>
      <Card title="보낸 알림">
        {list.data?.length === 0 && <Empty>아직 보낸 알림이 없어요.</Empty>}
        {list.data && list.data.length > 0 && (
          <DataTable head={['제목', '받는 사람', '인원', '읽음', '보낸 시각', '']}>
            {list.data.map((b) => (
              <tr key={b.id}>
                <td>{b.title}</td>
                <td>{b.targetFilter}</td>
                <td>{b.recipientCount}</td>
                <td>{b.readCount}/{b.recipientCount}</td>
                <td>{dateTime(b.createdAt)}</td>
                <td>{manager && <Button variant="line" size="sm" onClick={() => setView(b.id)}>읽음 현황</Button>}</td>
              </tr>
            ))}
          </DataTable>
        )}
      </Card>
      {view !== null && receipts.data && (
        <Card
          title={`읽음 현황 · ${receipts.data.title}`}
          right={<Button size="sm" onClick={() => remind.mutate(view)} busy={remind.isPending}>안 읽은 사람에게 다시 알리기</Button>}
        >
          <DataTable head={['이름', '받은 시각', '읽은 시각', '다시 알림']}>
            {(receipts.data.recipients as R[]).map((r) => (
              <tr key={r.applicationId}>
                <td>{r.actorName}</td>
                <td>{dateTime(r.deliveredAt)}</td>
                <td>{r.readAt ? dateTime(r.readAt) : <Chip tone="warn">아직 안 읽음</Chip>}</td>
                <td>{r.reminderCount}회</td>
              </tr>
            ))}
          </DataTable>
        </Card>
      )}
      {toast.node}
    </Page>
  );
}

/** ⭐ 출연자: 함께한 출연자를 저장해 두면 새 공고를 먼저 알려요(무료 30명). */
export function Stars() {
  const qc = useQueryClient();
  const toast = useToast();
  const q = useQuery({ queryKey: ['stars'], queryFn: () => api.get<R[]>(withCompany('/company/starred-actors')) });
  const del = useMutation({ mutationFn: (id: number) => api.del(withCompany(`/company/starred-actors/${id}`)), onSuccess: () => qc.invalidateQueries({ queryKey: ['stars'] }), onError: toast.fail });
  return (
    <Page title="⭐ 출연자" sub={`함께한 출연자를 저장하면 새 공고를 1분 먼저 알려요. ${q.data?.length ?? 0}/30명`}>
      <Card>
        {q.isPending && <Loading />}
        {q.data?.length === 0 && <Empty>아직 저장한 출연자가 없어요. 확정된 지원자 화면에서 ⭐ 로 저장해 보세요.</Empty>}
        {q.data && q.data.length > 0 && (
          <DataTable head={['이름', '메모', '확정', '노쇼', '']}>
            {q.data.map((s) => (
              <tr key={s.actorUserId}>
                <td>⭐ {s.name}</td>
                <td>{s.note}</td>
                <td>{s.confirmedCount}</td>
                <td>{s.noshowCount}</td>
                <td><Button variant="line" size="sm" onClick={() => del.mutate(s.actorUserId)}>해제</Button></td>
              </tr>
            ))}
          </DataTable>
        )}
      </Card>
      {toast.node}
    </Page>
  );
}

/** 확정 후 취소한 출연자의 패널티 판정(스케줄러만 투표, 한 명이라도 부과하면 공고당 1건). */
export function CancelReviews() {
  const qc = useQueryClient();
  const toast = useToast();
  const { company } = useSession();
  const scheduler = company?.memberRole === 'SCHEDULER';
  const q = useQuery({ queryKey: ['reviews'], queryFn: () => api.get<R[]>(withCompany('/company/cancellation-reviews')) });
  const vote = useMutation({
    mutationFn: (v: { id: number; vote: string }) => api.post<R>(withCompany(`/company/cancellation-reviews/${v.id}/vote`), { vote: v.vote }),
    onSuccess: (r) => (toast.show(r.status === 'PENALIZED' ? '패널티를 부과했어요.' : r.status === 'CLEARED' ? '부과하지 않기로 했어요.' : '투표했어요.'), qc.invalidateQueries({ queryKey: ['reviews'] })),
    onError: toast.fail,
  });
  return (
    <Page title="⚖️ 취소 판정" sub="확정한 출연자가 취소했어요. 패널티를 줄지 정해 주세요. 24시간 안에 정하지 않으면 부과하지 않아요.">
      {!scheduler && <Alert>투표는 스케줄러만 할 수 있어요. 여기서는 진행 상황만 볼 수 있어요.</Alert>}
      {q.isPending && <Loading />}
      {q.isError && <Alert tone="bad">{errorText(q.error)}</Alert>}
      {q.data?.length === 0 && <Empty>판정을 기다리는 건이 없어요.</Empty>}
      {q.data?.map((r) => (
        <Card key={r.applicationId} title={`${r.actorName} · ${r.postingTitle}`}>
          <p>취소 사유: {r.cancelReason}</p>
          <p className="cap">일정 {dateTime(r.workStartAt)} · 투표 {r.votes}명 {r.myVote ? `· 내 선택: ${r.myVote === 'PENALTY' ? '부과' : '미부과'}` : ''}</p>
          {scheduler && (
            <div className="actions">
              <Button variant="bad" onClick={() => vote.mutate({ id: r.applicationId, vote: 'PENALTY' })}>패널티 부과</Button>
              <Button variant="line" onClick={() => vote.mutate({ id: r.applicationId, vote: 'NO_PENALTY' })}>부과 안 함</Button>
            </div>
          )}
        </Card>
      ))}
      {toast.node}
    </Page>
  );
}

export function Tabbed({ tabs }: { tabs: { id: string; label: string; node: React.ReactNode }[] }) {
  const [t, setT] = useState(tabs[0].id);
  return (
    <>
      <Tabs value={t} onChange={setT} items={tabs.map((x) => ({ id: x.id, label: x.label }))} />
      {tabs.find((x) => x.id === t)!.node}
    </>
  );
}
