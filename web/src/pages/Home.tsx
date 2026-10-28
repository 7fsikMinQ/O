import { Link } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { api, withCompany } from '../api';
import { useSession } from '../session';
import { Alert, Card, Loading, Page } from '../ui';

type R = Record<string, any>; // eslint-disable-line @typescript-eslint/no-explicit-any

/** 업체 대시보드: 해야 할 일(지원자 대기·응답 대기·취소 판정·일정 충돌)을 먼저 보여 준다. */
export function Home() {
  const { company, me } = useSession();
  const q = useQuery({ queryKey: ['dashboard', company?.id], queryFn: () => api.get<R>(withCompany('/company/dashboard')), enabled: company?.status === 'APPROVED' });
  return (
    <Page title={`안녕하세요, ${me?.name ?? ''}님\u00A0👋`} sub={company ? `${company.name}` : ''}>
      {q.isPending && <Loading />}
      {q.data && (
        <>
          <div className="stats">
            <Link to="/jobs" className="statlink">
              <Card>
                <p className="cap">지원자 대기</p>
                <p className="stat">{q.data.waitingApplicants}</p>
                <p className="cap">합격 통보를 기다려요</p>
              </Card>
            </Link>
            <Link to="/jobs" className="statlink">
              <Card>
                <p className="cap">출연자 응답 대기</p>
                <p className="stat">{q.data.awaitingActorResponse}</p>
                <p className="cap">합격 통보 후 확정 전</p>
              </Card>
            </Link>
            <Link to="/reviews" className="statlink">
              <Card>
                <p className="cap">취소 판정 대기</p>
                <p className="stat">{q.data.pendingCancelReviews}</p>
                <p className="cap">스케줄러 투표가 필요해요</p>
              </Card>
            </Link>
            <Card>
              <p className="cap">일정 겹침 경고</p>
              <p className="stat">{q.data.unresolvedConflicts}</p>
              <p className="cap">출연자가 알고 확정한 건</p>
            </Card>
          </div>
          <Card title="공고 현황">
            <div className="chips">
              {(q.data.postingsByStatus as R[]).map((s) => (
                <span key={s.status} className="chip">
                  {s.status} {s.n}
                </span>
              ))}
              {(q.data.postingsByStatus as R[]).length === 0 && <span className="cap">아직 공고가 없어요.</span>}
            </div>
          </Card>
        </>
      )}
      {!company && <Alert>속한 업체가 없어요. 아래에서 업체를 등록하거나 초대를 수락해 주세요.</Alert>}
    </Page>
  );
}
