// 시간은 서버가 UTC, 화면은 한국 시간(KST). 금액은 원 정수.
const KST = 'Asia/Seoul';

const dtf = new Intl.DateTimeFormat('ko-KR', { timeZone: KST, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false });
const df = new Intl.DateTimeFormat('ko-KR', { timeZone: KST, month: 'long', day: 'numeric', weekday: 'short' });

export function dateTime(iso?: string | null): string {
  if (!iso) return '';
  const p = Object.fromEntries(dtf.formatToParts(new Date(iso)).map((x) => [x.type, x.value]));
  return `${p.year}-${p.month}-${p.day} ${p.hour === '24' ? '00' : p.hour}:${p.minute}`;
}

export function dateOnly(iso?: string | null): string {
  return iso ? df.format(new Date(iso)) : '';
}

export function timeOnly(iso?: string | null): string {
  return iso ? new Intl.DateTimeFormat('ko-KR', { timeZone: KST, hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(iso)) : '';
}

export const won = (n?: number | null): string => (n == null ? '' : `${n.toLocaleString('ko-KR')}원`);

/** <input type="datetime-local"> 값(KST 로 해석) → 서버로 보낼 ISO(+09:00). */
export const kstInputToIso = (v: string): string => `${v.length === 16 ? `${v}:00` : v}+09:00`;

/** 서버 ISO → <input type="datetime-local"> 값(KST). */
export function isoToKstInput(iso: string): string {
  const d = new Date(new Date(iso).getTime() + 9 * 3600 * 1000);
  return d.toISOString().slice(0, 16);
}

export const STATUS_LABEL: Record<string, string> = {
  APPLIED: '지원함', SELECTED: '합격 통보', CONFIRMED: '확정', REJECTED: '불합격', AUTO_REJECTED: '마감 불합격', CANCELED_BY_ACTOR: '출연자 취소',
  CANCELED_BY_COMPANY: '업체 취소', CANCELED_BY_SYSTEM: '일정 연기로 이동', EXPIRED: '응답 만료',
  DRAFT: '임시저장', OPEN: '모집 중', CLOSED: '마감', CANCELED: '취소', COMPLETED: '완료', PENDING: '승인 대기', APPROVED: '승인됨',
};

export const ROLE_LABEL: Record<string, string> = { OWNER: '대표', MANAGER: '팀장', SCHEDULER: '스케줄러', FIELD_LEADER: '현장 반장', STAFF: '스태프' };

export const GENDER_LABEL: Record<string, string> = { MALE: '남', FEMALE: '여', OTHER: '기타', ANY: '무관' };

export function ageOf(birthDate?: string | null): number | null {
  if (!birthDate) return null;
  const b = new Date(birthDate);
  const now = new Date(Date.now() + 9 * 3600 * 1000);
  let a = now.getUTCFullYear() - b.getUTCFullYear();
  if (now.getUTCMonth() < b.getUTCMonth() || (now.getUTCMonth() === b.getUTCMonth() && now.getUTCDate() < b.getUTCDate())) a -= 1;
  return a;
}
