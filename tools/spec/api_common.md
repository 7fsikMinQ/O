## 0. 공통 규약

| 항목 | 규칙 |
|---|---|
| 기본 URL | `https://api.{도메인}/api/v1` (개발: `http://localhost:8080/api/v1`) |
| 인증 | `Authorization: Bearer {accessToken}` (앱). 웹은 HttpOnly+Secure+SameSite=Lax 쿠키 + CSRF 토큰 헤더 `X-CSRF-Token`. access 15분, refresh 14일(회전, 재사용 감지 시 전 기기 폐기) |
| 형식 | JSON(UTF-8), 필드 camelCase, 날짜 `YYYY-MM-DD`, 시각 ISO-8601(UTC `Z`) 또는 촬영 업무값은 `HH:mm` KST, 금액 정수(원) |
| 페이지네이션 | 커서 방식 `?cursor=...&size=20(최대 100)` → `{items:[], nextCursor:null|string}` |
| 멱등성 | 지원·일괄 처리·출석 기록·메시지 발송은 `Idempotency-Key: {uuid}` 헤더 지원(24시간 저장) |
| 오류 | RFC 7807 `application/problem+json`: `{type,title,status,code,detail,errors:[{field,code,message}],traceId}` |
| 부분 성공 | 일괄 API 는 `207` + `{succeeded:[],failed:[{id,code,message}]}` |
| 버전 | URL 메이저 버전. 하위 호환 변경만 v1 에 추가, 파괴적 변경은 v2 |
| 속도 제한 | 로그인·인증번호·지원은 IP/계정별 제한(429 + `Retry-After`) |
| 문서화 | 실제 스키마는 springdoc 이 `GET /v3/api-docs` 로 제공하고 이 문서와 CI 에서 대조. 웹은 `openapi-typescript`, 앱은 `openapi-generator`(dart-dio)로 클라이언트 생성 |
| 개인정보 | 마스킹은 서버가 한다. 클라이언트는 받은 값을 그대로 표시(이중 마스킹 금지). 로그에는 전화·계좌 기록 금지 |

### 공통 오류 코드
| HTTP | code | 의미 |
|---|---|---|
| 400 | `VALIDATION_FAILED` | 입력 검증 실패 (`errors[]` 참조) |
| 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | 인증 필요/만료 |
| 403 | `FORBIDDEN` / `COMPANY_NOT_APPROVED` / `PLAN_REQUIRED` / `NOT_ASSIGNED_FIELD_LEADER` | 권한·승인·플랜·담당 아님 |
| 404 | `NOT_FOUND` | 대상 없음(권한 없는 리소스도 존재 은닉 위해 404 가능) |
| 409 | `FULL` / `TIME_CONFLICT` / `ALREADY_APPLIED` / `STATE_CONFLICT` / `DUPLICATE` | 정원·시간 겹침·중복·상태 충돌 |
| 422 | `ELIGIBILITY_FAILED` (+ 사유 코드 목록, 부록 D) | 지원 자격 미충족 |
| 429 | `RATE_LIMITED` | 호출 제한 |
| 5xx | `INTERNAL_ERROR` | 서버 오류(`traceId` 로 추적) |

