import { describe, expect, it } from 'vitest';
import { ageOf, dateTime, kstInputToIso, isoToKstInput, won } from './format';
import { ApiError } from './api';
import { errorText } from './ui';

describe('format', () => {
  it('한국 시간으로 보여 준다', () => {
    expect(dateTime('2026-12-20T00:00:00Z')).toBe('2026-12-20 09:00');
    expect(dateTime(null)).toBe('');
  });
  it('입력값과 ISO 를 KST 기준으로 오간다', () => {
    expect(kstInputToIso('2026-12-20T09:00')).toBe('2026-12-20T09:00:00+09:00');
    expect(isoToKstInput('2026-12-20T00:00:00Z')).toBe('2026-12-20T09:00');
  });
  it('금액은 원 단위 쉼표로', () => {
    expect(won(10320)).toBe('10,320원');
    expect(won(null)).toBe('');
  });
  it('만 나이를 센다', () => {
    expect(ageOf('1990-01-01')).toBeGreaterThanOrEqual(35);
    expect(ageOf(undefined)).toBeNull();
  });
});

describe('errorText', () => {
  it('서버가 준 쉬운 말을 우선한다', () => {
    expect(errorText(new ApiError(400, { detail: '시급은 최저시급 이상으로 입력해 주세요.' }))).toContain('최저시급');
  });
  it('본문이 없으면 상태별 안내', () => {
    expect(errorText(new ApiError(403, {}))).toContain('권한');
    expect(errorText(new ApiError(429, {}))).toContain('잠시');
    expect(errorText(new TypeError('network'))).toContain('인터넷');
  });
});
