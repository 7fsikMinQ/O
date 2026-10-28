# 08. API 명세서
> 이 문서는 `tools/spec/*.py` 와 `db/schema.sql` 에서 `python3 tools/build_docs.py` 로 **자동 생성**된다. 직접 수정하지 말고 원천 데이터를 고칠 것.
> 버전 v1.0 · 기준일 2026-10-28

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


총 **124**개 엔드포인트. 권한 약어: PUB 비로그인 / USER 로그인 / ACTOR 출연자 / CMP 승인 업체 구성원 / CMP-M 업체 OWNER·MANAGER / CMP-F 업체 OWNER·MANAGER·담당 FIELD_LEADER / OPR 운영자 이상 / ADM 관리자.

## 인증·계정
| ID | 메서드 | 경로 | 이름 | 권한 | 요청/응답 요약 | 테이블 | 주요 오류 | 구현 기능 |
|---|---|---|---|---|---|---|---|---|
| API-AU01 | POST | `/auth/signup` | 이메일 가입 | PUB | {email,password,name,phone?,type:ACTOR\|COMPANY,consents[]} → 201 {userId} | users,user_roles,user_consents | 409 중복이메일/휴대폰, 422 필수약관 미동의 | F-AU01, F-AU03, F-AU04 |
| API-AU02 | POST | `/auth/login` | 로그인 | PUB | {email,password,deviceId} → {accessToken,refreshToken(앱)/쿠키(웹),roles} | users,refresh_tokens,login_histories | 401, 423 정지계정, 429 | F-AU01, F-SY01 |
| API-AU03 | POST | `/auth/social/{provider}` | 소셜 로그인 | PUB | {idToken\|code} → 토큰 또는 {needSignup:true,signupToken} | user_auth_providers,users | 401 | F-AU02 |
| API-AU04 | POST | `/auth/social/{provider}/signup` | 소셜 가입 완료 | PUB | {signupToken,type,phone,consents[]} → 토큰 | users,user_auth_providers,user_roles,user_consents | 409, 422 | F-AU02 |
| API-AU05 | POST | `/auth/refresh` | 토큰 갱신(회전) | PUB | {refreshToken} → 새 토큰쌍 | refresh_tokens | 401 재사용 감지 시 전체 폐기 | F-AU08 |
| API-AU06 | POST | `/auth/logout` | 로그아웃 | USER | {refreshToken?, all?:bool} → 204 | refresh_tokens,device_tokens |  | F-AU08 |
| API-AU07 | POST | `/auth/password/reset-request` | 재설정 메일 요청 | PUB | {email} → 202(항상) | users,outbox_events |  | F-AU05 |
| API-AU08 | POST | `/auth/password/reset` | 비밀번호 재설정 | PUB | {token,newPassword} → 204 | users,refresh_tokens | 400 만료 | F-AU05 |
| API-AU09 | POST | `/auth/phone/send` | 휴대폰 인증번호 발송 | USER | {phone} → 202 | users | 409 중복번호, 429 | F-AU07 |
| API-AU10 | POST | `/auth/phone/verify` | 휴대폰 인증 확인 | USER | {phone,code} → 204 | users | 400 | F-AU07 |
| API-AU11 | DELETE | `/me` | 회원 탈퇴 | USER | {password?,reason} → 204 | users,applications | 409 진행 중 확정 건 | F-AU06 |
| API-AU12 | GET | `/terms` | 약관 목록(최신) | PUB | → [{type,version,content,isRequired}] | terms |  | F-AU04 |

## 내 정보·프로필·설정
| ID | 메서드 | 경로 | 이름 | 권한 | 요청/응답 요약 | 테이블 | 주요 오류 | 구현 기능 |
|---|---|---|---|---|---|---|---|---|
| API-ME01 | GET | `/me` | 내 정보 | USER | → {user,roles,companies[],actorProfileCompleted} | users,user_roles,company_members |  | F-AU03 |
| API-ME02 | GET | `/me/actor-profile` | 내 출연자 프로필 | ACTOR | → 프로필+사진+통계 | actor_profiles,file_attachments |  | F-PR01 |
| API-ME03 | PUT | `/me/actor-profile` | 프로필 저장 | ACTOR | {birthDate,gender,heightCm,weightKg,sizes,hairStyle,hasTattoo,canDrive,hasVehicle,regionId,tags[],intro} | actor_profiles | 422 | F-PR01 |
| API-ME04 | PUT | `/me/actor-profile/payout` | 기본 정산·출발 정보 저장 | ACTOR | {bankCode,accountNumber,holder,defaultDepartureLocation} (서버 암호화) | actor_payout_accounts,actor_profiles | 422 | F-PR03 |
| API-ME05 | GET | `/me/actor-profile/payout` | 기본 정산 정보 조회(계좌 마스킹) | ACTOR | → {bankName,accountMasked,holder} | actor_payout_accounts |  | F-PR03 |
| API-ME06 | PUT | `/me/actor-profile/disclosure-consent` | 이력 공개 동의 변경 | ACTOR | {agree:bool} → 204 | actor_profiles,user_consents,audit_logs |  | F-PR05 |
| API-ME07 | GET | `/me/activity` | 내 활동 이력·성실도 | ACTOR | → {confirmed,attended,noshow,reliabilityScore,events[]} | actor_profiles,reliability_events,applications |  | F-PR04, F-OP09 |
| API-ME08 | POST | `/me/guardian-consents` | 법정대리인 동의서 제출 | ACTOR | {guardianName,guardianPhone,relationship,fileIds[]} → 201 | guardian_consents,file_attachments | 422 파일누락 | F-PR06 |
| API-ME09 | GET | `/me/guardian-consents` | 동의서 상태 조회 | ACTOR | → [{status,validUntil,rejectReason}] | guardian_consents |  | F-AU09, F-PR06 |
| API-ME10 | PUT | `/me/notification-settings` | 알림 수신 설정 | USER | {items:[{type,channel,enabled}]} | notification_settings |  | F-NT04 |
| API-ME11 | GET | `/me/notification-settings` | 알림 수신 설정 조회 | USER | → items[] | notification_settings |  | F-NT04 |
| API-ME12 | POST | `/me/devices` | 푸시 토큰 등록 | USER | {platform,token} | device_tokens |  | F-NT02 |
| API-ME13 | DELETE | `/me/devices/{token}` | 푸시 토큰 해제 | USER |  | device_tokens |  | F-NT02 |

## 파일
| ID | 메서드 | 경로 | 이름 | 권한 | 요청/응답 요약 | 테이블 | 주요 오류 | 구현 기능 |
|---|---|---|---|---|---|---|---|---|
| API-FL01 | POST | `/files/presign` | 업로드 URL 발급 | USER | {filename,contentType,size,purpose} → {fileId,uploadUrl,expiresAt} | files | 422 타입/용량 초과 | F-PR02, F-CO01, F-SY08 |
| API-FL02 | POST | `/files/{fileId}/complete` | 업로드 완료 확인 | USER | → 200 (존재·크기·MIME 검증 후 ACTIVE) | files | 409 미업로드 | F-PR02, F-SY08 |
| API-FL03 | GET | `/files/{fileId}/url` | 서명 조회 URL | USER | → {url,expiresAt} (권한 검사) | files,file_attachments | 403 | F-PR02 |
| API-FL04 | DELETE | `/files/{fileId}` | 파일 삭제 | USER | → 204 (소유/권한) | files,file_attachments | 403 | F-PR02 |

## 업체
| ID | 메서드 | 경로 | 이름 | 권한 | 요청/응답 요약 | 테이블 | 주요 오류 | 구현 기능 |
|---|---|---|---|---|---|---|---|---|
| API-CO01 | POST | `/companies` | 업체 등록 | USER | {name,bizRegNo,representative,phone,address,licenseFileId} → 201 PENDING | companies,company_members,company_verifications,file_attachments | 409 사업자번호 중복 | F-CO01 |
| API-CO02 | GET | `/companies/{id}` | 업체 프로필 조회 | USER | → 공개 프로필 | companies |  | F-CO04 |
| API-CO03 | PATCH | `/companies/{id}` | 업체 프로필 수정 | CMP-M |  | companies |  | F-CO04 |
| API-CO04 | GET | `/companies/{id}/members` | 직원 목록 | CMP-M |  | company_members |  | F-CO03 |
| API-CO05 | POST | `/companies/{id}/members` | 직원 초대 | CMP-M | {email,memberRole,departmentName} | company_members,outbox_events | 404, 409 | F-CO03 |
| API-CO06 | PATCH | `/companies/{id}/members/{userId}` | 직책 변경/비활성 | CMP-M | {memberRole?,status?} | company_members | 403 OWNER 보호 | F-CO03 |
| API-CO07 | GET | `/company/blocks` | 차단 목록 | CMP-M |  | company_actor_blocks |  | F-CO05 |
| API-CO08 | PUT | `/company/blocks/{actorUserId}` | 출연자 차단 | CMP-M | {reason} | company_actor_blocks |  | F-CO05 |
| API-CO09 | DELETE | `/company/blocks/{actorUserId}` | 차단 해제 | CMP-M |  | company_actor_blocks |  | F-CO05 |

## 공고 관리(업체)
| ID | 메서드 | 경로 | 이름 | 권한 | 요청/응답 요약 | 테이블 | 주요 오류 | 구현 기능 |
|---|---|---|---|---|---|---|---|---|
| API-JB01 | POST | `/company/jobs` | 공고 생성(임시저장) | CMP | {productionId\|newProduction,title,description,isUrgent,concealDetails,contactPhone,applyDeadlineAt,schedules[{shootDate,callTime,endTime,isOvernight,meeting*,gatheringType,fieldLeaderUserId,positions[{title,headcount,standbyHeadcount,genderReq,birthYearMin/Max,heightMin/Max,allowMinor,pay*,overtime*,meal*,dressCode,requiresCostumePhoto,applyMode,callTime?,meetingPlace?}]}]} → 201 | job_postings,job_schedules,job_positions,productions | 422 | F-CO06, F-JB01, F-JB03, F-JB06 |
| API-JB02 | PUT | `/company/jobs/{id}` | 공고 수정 | CMP | 전체 교체(지원자 있는 역할의 정원 감소·삭제는 거부) | job_postings,job_schedules,job_positions | 409 지원자 존재 | F-CO06, F-JB01 |
| API-JB03 | POST | `/company/jobs/{id}/publish` | 공고 게시 | CMP | → OPEN (승인 업체만) | job_postings,outbox_events | 403 미승인 업체, 422 필수 누락 | F-JB04 |
| API-JB04 | POST | `/company/jobs/{id}/copy` | 공고 복사 | CMP | {shiftDays\|dates[]} → 새 DRAFT | job_postings,job_schedules,job_positions |  | F-JB02 |
| API-JB05 | POST | `/company/jobs/{id}/close` | 공고 마감 | CMP | → CLOSED + 자동 불합격 처리 결과 {autoRejected:n} | job_postings,job_positions,applications,application_status_histories,outbox_events |  | F-JB04, F-SL05 |
| API-JB06 | POST | `/company/jobs/{id}/cancel` | 공고 취소 | CMP-M | {reason} → 전체 지원자 알림 | job_postings,applications,outbox_events |  | F-JB04 |
| API-JB07 | PATCH | `/company/schedules/{id}/status` | 일자 취소/연기/마감 | CMP | {status:CLOSED\|CANCELED\|POSTPONED,newDate?,reason} | job_schedules,applications,outbox_events |  | F-JB05 |
| API-JB08 | POST | `/company/positions/{id}/close` | 역할 마감 | CMP | → 해당 역할 자동 불합격 | job_positions,applications |  | F-JB04, F-SL05 |
| API-JB09 | GET | `/company/jobs` | 내 업체 공고 목록 | CMP | ?status&page → 커서 페이징 | job_postings |  | F-JB04 |
| API-JB10 | GET | `/company/jobs/{id}` | 내 공고 상세(편집용) | CMP |  | job_postings,job_schedules,job_positions |  | F-JB01 |
| API-JB11 | GET | `/company/dashboard` | 업체 대시보드 집계 | CMP | → 오늘/내일 현장, 미처리 지원자 수, 미응답자 수 | job_schedules,applications |  | F-JB07 |

## 공고 탐색(공개)
| ID | 메서드 | 경로 | 이름 | 권한 | 요청/응답 요약 | 테이블 | 주요 오류 | 구현 기능 |
|---|---|---|---|---|---|---|---|---|
| API-JS01 | GET | `/jobs/calendar` | 월 달력 집계 | PUB | ?month=2026-11&region&category → [{date,postings,openSlots,hasUrgent}] | job_schedules,job_positions,job_postings |  | F-SR01 |
| API-JS02 | GET | `/jobs` | 공고 목록·검색 | PUB | ?date\|from&to&regionIds&categoryIds&gender&birthYear&height&minPay&urgent&meal&transport&q&matchMe&cursor | job_postings,job_schedules,job_positions |  | F-SR02, F-SR03 |
| API-JS03 | GET | `/jobs/{id}` | 공고 상세 | PUB | → 일자·역할·조건충족여부(로그인 시)·비공개 마스킹 | job_postings,job_schedules,job_positions |  | F-JB03, F-SR04 |
| API-JS04 | POST | `/jobs/{id}/bookmark` | 관심 등록 | ACTOR |  | bookmarks |  | F-SR05 |
| API-JS05 | DELETE | `/jobs/{id}/bookmark` | 관심 해제 | ACTOR |  | bookmarks |  | F-SR05 |
| API-JS06 | GET | `/me/bookmarks` | 관심 공고 목록 | ACTOR |  | bookmarks,job_postings |  | F-SR05 |
| API-JS07 | GET | `/me/saved-searches` | 저장 검색 목록 | ACTOR |  | saved_searches |  | F-SR07 |
| API-JS08 | PUT | `/me/saved-searches/{id}` | 저장 검색 저장 | ACTOR | {name,conditions,notify} | saved_searches |  | F-SR07 |
| API-JS09 | DELETE | `/me/saved-searches/{id}` | 저장 검색 삭제 | ACTOR |  | saved_searches |  | F-SR07 |

## 지원(출연자)
| ID | 메서드 | 경로 | 이름 | 권한 | 요청/응답 요약 | 테이블 | 주요 오류 | 구현 기능 |
|---|---|---|---|---|---|---|---|---|
| API-AP01 | GET | `/positions/{id}/apply-form` | 지원 폼 사전정보 | ACTOR | → 프로필 기반 자동 입력값, 요건 충족 여부, 필요한 첨부, 같은 날 겹침 경고 | actor_profiles,actor_payout_accounts,applications,guardian_consents |  | F-AU09, F-AP01 |
| API-AP02 | POST | `/positions/{id}/applications` | 지원 | ACTOR | {name,phone,departureLocation,bank*,accountNumber,message,costumePhotoFileIds[]} (Idempotency-Key) → 201 {status:APPLIED\|CONFIRMED} | applications,job_positions,file_attachments,application_status_histories,outbox_events | 403 차단/미성년 미검증, 409 중복/정원, 422 요건 | F-AU09, F-AP02, F-SL07 |
| API-AP03 | DELETE | `/applications/{id}` | 지원 취소 | ACTOR | {reason?} → 204 (확정 후 취소는 reason 필수) | applications,reliability_events,outbox_events | 409 | F-AP03 |
| API-AP04 | GET | `/me/applications` | 내 지원 목록 | ACTOR | ?status&from&to | applications,job_postings |  | F-AP04 |
| API-AP05 | GET | `/me/applications/{id}` | 내 지원/합격 상세 | ACTOR | → 합격 시 비공개 정보·반장 연락처·공지 | applications,job_schedules,job_broadcasts |  | F-AP04, F-OP13 |
| API-AP06 | GET | `/me/calendar` | 내 일정 달력 | ACTOR | ?month → [{date,status[]}] | applications |  | F-SR06 |
| API-AP07 | POST | `/applications/{id}/departure-confirm` | 전날 참석 확인 | ACTOR | → 204 | applications | 409 기간 외 | F-AP05 |

## 선발(업체)
| ID | 메서드 | 경로 | 이름 | 권한 | 요청/응답 요약 | 테이블 | 주요 오류 | 구현 기능 |
|---|---|---|---|---|---|---|---|---|
| API-SL01 | GET | `/company/positions/{id}/applications` | 역할별 지원자 목록 | CMP | ?status&sort&view=card\|grid → 마스킹 적용 목록 | applications,actor_profiles |  | F-SL01, F-OP13 |
| API-SL02 | GET | `/company/applications/{id}` | 지원자 상세 | CMP | → 프로필·사진·시간겹침 배지(합격 후 연락처 공개) | applications,actor_profiles,file_attachments |  | F-SL02, F-OP13 |
| API-SL03 | GET | `/company/actors/{userId}/history` | 지원자 역대 합격·출석 이력 | CMP | 동의한 출연자만, 최근 50건 | applications,attendances,actor_profiles | 403 동의 철회 | F-SL02 |
| API-SL04 | GET | `/company/actors/{userId}/applications` | 지원자 타 공고 지원 목록 | CMP | ?date → 타 업체 공고는 제목 마스킹, 시간대·상태 중심 | applications,job_schedules,company_subscriptions,plan_features,app_settings | 403 구독 필요 | F-SL03, F-SY09 |
| API-SL05 | POST | `/company/applications/decide` | 일괄 처리(합격·예비·불합격·보류) | CMP | {applicationIds[],decision:CONFIRM\|STANDBY\|REJECT\|SHORTLIST,reason?} → {succeeded[],failed[{id,code}]} | applications,job_positions,application_status_histories,outbox_events,audit_logs | 207 부분 성공, 409 정원/시간겹침 | F-SL04, F-SL07 |
| API-SL06 | POST | `/company/applications/{id}/cancel` | 합격 취소(업체) | CMP-M | {reason} | applications,job_positions,outbox_events |  | F-SL06 |
| API-SL07 | POST | `/company/applications/{id}/promote` | 예비 → 합격 승격 | CMP |  | applications,job_positions |  | F-SL06 |

## 합격 이후·현장
| ID | 메서드 | 경로 | 이름 | 권한 | 요청/응답 요약 | 테이블 | 주요 오류 | 구현 기능 |
|---|---|---|---|---|---|---|---|---|
| API-OP01 | GET | `/company/schedules/{id}/headcount` | 인원표 | CMP | → 역할별 집계 | v_schedule_headcount_summary |  | F-OP01 |
| API-OP02 | GET | `/company/schedules/{id}/roster` | 인원 정보표 | CMP-F | ?position&q → 합격자 상세(계좌는 CMP-M만, 마스킹 규칙 적용) | v_schedule_confirmed_roster,audit_logs |  | F-OP02, F-OP13 |
| API-OP03 | GET | `/company/schedules/{id}/roster.xlsx` | 인원 정보표 엑셀 | CMP-M | → xlsx (감사로그 기록) | v_schedule_confirmed_roster,audit_logs,plan_features | 403 권한/플랜 | F-OP03, F-SY09 |
| API-OP04 | POST | `/company/broadcasts` | 합격자 단체 메시지 발송 | CMP-F | {scheduleId\|positionId,targetFilter,title,body,channels[]} → 202 {broadcastId,recipientCount} | job_broadcasts,notifications,outbox_events,plan_features,usage_counters | 429 월 한도 | F-OP04, F-SY09 |
| API-OP05 | GET | `/company/broadcasts` | 발송 내역 | CMP-F | ?scheduleId → 수신/읽음 수 | job_broadcasts,notifications |  | F-OP04 |
| API-OP06 | GET | `/company/schedules/{id}/departure-pending` | 참석 미응답자 목록 | CMP-F | → 이름·전화 | applications |  | F-OP05 |
| API-OP07 | PUT | `/company/schedules/{id}/attendances` | 출석 일괄 기록 | CMP-F | {items:[{applicationId,result,checkinAt?,note?}]} (멱등) | attendances,reliability_events,outbox_events | 403 담당 아님 | F-OP06, F-OP09 |
| API-OP08 | PUT | `/company/schedules/{id}/checkout` | 퇴근·연장 일괄 입력 | CMP-F | {applicationIds[],checkoutAt} | attendances |  | F-OP08 |
| API-OP09 | PUT | `/company/applications/{id}/settlement` | 정산 기록 저장 | CMP-M | {items:[{type,amount,quantity,memo}],status} | settlements,settlement_items |  | F-OP10 |
| API-OP10 | GET | `/company/schedules/{id}/settlements.xlsx` | 정산 엑셀(주민번호 제외) | CMP-M |  | settlements,settlement_items,audit_logs |  | F-OP10 |
| API-OP11 | POST | `/applications/{id}/reviews` | 평가 작성 | USER | {score,comment} | reviews | 409 중복/미출석 | F-OP11 |
| API-OP12 | POST | `/company/checkin-qr` | 일자 QR 발급 | CMP-F | {scheduleId} → 서명된 단기 토큰 | attendances |  | F-OP07 |
| API-OP13 | POST | `/me/checkin` | QR/GPS 체크인 | ACTOR | {token,lat?,lng?} | attendances | 403 범위 밖 | F-OP07 |

## 알림
| ID | 메서드 | 경로 | 이름 | 권한 | 요청/응답 요약 | 테이블 | 주요 오류 | 구현 기능 |
|---|---|---|---|---|---|---|---|---|
| API-NT01 | GET | `/notifications` | 알림함 | USER | ?cursor&unread | notifications |  | F-NT01 |
| API-NT02 | PATCH | `/notifications/{id}/read` | 읽음 처리 | USER |  | notifications |  | F-NT01 |
| API-NT03 | POST | `/notifications/read-all` | 전체 읽음 | USER |  | notifications |  | F-NT01 |
| API-NT04 | GET | `/notifications/unread-count` | 미읽음 수 | USER |  | notifications |  | F-NT01 |

## 게시판·신고
| ID | 메서드 | 경로 | 이름 | 권한 | 요청/응답 요약 | 테이블 | 주요 오류 | 구현 기능 |
|---|---|---|---|---|---|---|---|---|
| API-BD01 | GET | `/boards` | 게시판 목록 | USER | → 권한 반영 | boards,board_permissions |  | F-BD01 |
| API-BD02 | GET | `/boards/{code}/posts` | 글 목록 | USER | ?cursor&q | posts,board_permissions |  | F-BD01, F-BD03 |
| API-BD03 | POST | `/boards/{code}/posts` | 글 작성 | USER | {title,content,isSecret,fileIds[]} | posts,file_attachments,board_permissions | 403 | F-BD01, F-BD02, F-BD03 |
| API-BD04 | GET | `/posts/{id}` | 글 상세 | USER |  | posts,comments | 403 비밀글 | F-BD01, F-BD02 |
| API-BD05 | PUT | `/posts/{id}` | 글 수정 | USER | 본인 | posts |  | F-BD03 |
| API-BD06 | DELETE | `/posts/{id}` | 글 삭제 | USER | 본인/운영자 | posts |  | F-BD03 |
| API-BD07 | POST | `/posts/{id}/comments` | 댓글 작성 | USER | {content,parentId?} | comments,posts |  | F-BD03 |
| API-BD08 | DELETE | `/comments/{id}` | 댓글 삭제 | USER |  | comments |  | F-BD03 |
| API-BD09 | POST | `/reports` | 신고 | USER | {targetType,targetId,reasonCode,detail} | reports |  | F-BD04 |

## 관리자
| ID | 메서드 | 경로 | 이름 | 권한 | 요청/응답 요약 | 테이블 | 주요 오류 | 구현 기능 |
|---|---|---|---|---|---|---|---|---|
| API-AD01 | GET | `/admin/companies` | 업체 승인 대기/목록 | ADM | ?status | companies,company_verifications |  | F-CO02 |
| API-AD02 | POST | `/admin/companies/{id}/approve` | 업체 승인 | ADM |  | companies,company_verifications,audit_logs,outbox_events |  | F-CO02 |
| API-AD03 | POST | `/admin/companies/{id}/reject` | 업체 반려 | ADM | {reason} | companies,company_verifications,audit_logs,outbox_events |  | F-CO02 |
| API-AD04 | GET | `/admin/users` | 회원 검색 | ADM | ?q&status&role | users,user_roles |  | F-AD01 |
| API-AD05 | POST | `/admin/users/{id}/suspend` | 회원 정지 | ADM | {reason,until?} | users,audit_logs,refresh_tokens |  | F-AD01 |
| API-AD06 | POST | `/admin/users/{id}/restore` | 정지 해제 | ADM |  | users,audit_logs |  | F-AD01 |
| API-AD07 | PUT | `/admin/users/{id}/roles` | 역할 부여/회수 | ADM | {roles[]} | user_roles,audit_logs |  | F-AD01 |
| API-AD08 | GET | `/admin/jobs` | 공고 모니터링 | ADM | ?q&status | job_postings |  | F-AD02 |
| API-AD09 | POST | `/admin/jobs/{id}/hide` | 공고 숨김 | ADM | {reason} | job_postings,audit_logs |  | F-AD02 |
| API-AD10 | GET | `/admin/reports` | 신고 목록 | OPR |  | reports |  | F-AD03 |
| API-AD11 | POST | `/admin/reports/{id}/resolve` | 신고 처리 | OPR | {action,note} | reports,audit_logs |  | F-AD03 |
| API-AD12 | PUT | `/admin/boards/{id}` | 게시판 설정·권한 | ADM |  | boards,board_permissions |  | F-AD04 |
| API-AD13 | POST | `/admin/terms` | 약관 버전 등록 | ADM |  | terms,audit_logs |  | F-AD04 |
| API-AD14 | GET | `/admin/settings` | 운영 설정 조회 | ADM |  | app_settings |  | F-AD06 |
| API-AD15 | PUT | `/admin/settings/{key}` | 운영 설정 변경 | ADM | {value} | app_settings,audit_logs |  | F-AD06 |
| API-AD16 | GET | `/admin/audit-logs` | 감사로그 검색 | ADM | ?actor&action&entity&from&to | audit_logs |  | F-AD05 |
| API-AD17 | GET | `/admin/guardian-consents` | 동의서 검수 대기 | OPR |  | guardian_consents |  | F-PR06 |
| API-AD18 | POST | `/admin/guardian-consents/{id}/verify` | 동의서 승인/반려 | OPR | {approve,rejectReason?} | guardian_consents,actor_profiles,audit_logs,outbox_events |  | F-PR06 |
| API-AD19 | PUT | `/admin/plans/{id}` | 요금제·기능 편집 | ADM | {features[]} | subscription_plans,plan_features,audit_logs |  | F-AD08 |
| API-AD20 | GET | `/admin/stats` | 운영 통계 | ADM | ?from&to | applications,job_postings,attendances |  | F-AD07 |
| API-AD21 | POST | `/admin/productions/{id}/merge` | 작품 병합 | ADM | {intoId} | productions,job_postings |  | F-AD04 |
| API-AD22 | POST | `/admin/inquiries/{postId}/answer` | 문의 답변 | OPR | {content} | comments,posts,notifications |  | F-BD02 |

## 마스터
| ID | 메서드 | 경로 | 이름 | 권한 | 요청/응답 요약 | 테이블 | 주요 오류 | 구현 기능 |
|---|---|---|---|---|---|---|---|---|
| API-MS01 | GET | `/master/regions` | 지역 | PUB |  | regions |  | F-JB01 |
| API-MS02 | GET | `/master/position-categories` | 역할 유형 | PUB |  | position_categories |  | F-JB01 |
| API-MS03 | GET | `/master/productions` | 작품 검색 | CMP | ?q → 자동완성 | productions |  | F-JB01 |
| API-MS04 | GET | `/master/banks` | 은행 코드 | USER |  |  |  | F-PR03 |

## 부록. 핵심 API 상세 예시

### A. 달력 집계 — `GET /jobs/calendar?month=2026-11&regionIds=11,41`
```json
{
  "month": "2026-11",
  "days": [
    {"date": "2026-11-10", "postings": 4, "openSlots": 37, "hasUrgent": true},
    {"date": "2026-11-11", "postings": 2, "openSlots": 12, "hasUrgent": false}
  ]
}
```
집계는 `OPEN` 공고·`OPEN` 일자·`OPEN` 역할만 포함하고 `headcount - confirmed_count` 합을 `openSlots` 로 한다. 응답은 30~60초 캐시 가능(개인화 없음).

### B. 지원 폼 사전정보 — `GET /positions/{id}/apply-form`
```json
{
  "eligible": false,
  "reasons": ["GUARDIAN_CONSENT_REQUIRED"],
  "prefill": {"name": "홍길동", "phone": "010-1234-5678", "departureLocation": "서울 사당역 인근",
              "bankName": "국민은행", "accountMasked": "123-****-**89", "accountHolder": "홍길동"},
  "required": {"costumePhoto": true, "document": false, "guardianConsent": true},
  "warnings": [{"code": "OVERLAPS_MY_CONFIRMED", "applicationId": 9812, "range": "2026-11-10T06:00~12:00+09:00"}]
}
```

### C. 지원 — `POST /positions/{id}/applications`
요청 (헤더 `Idempotency-Key`)
```json
{
  "name": "홍길동", "phone": "010-1234-5678",
  "departureLocation": "사당역 1번 출구 셔틀 탑승 희망",
  "bankCode": "004", "accountNumber": "12345678901", "accountHolder": "홍길동",
  "message": "검정 정장 보유", "costumePhotoFileIds": ["b1f6..."]
}
```
응답 `201` `{"applicationId": 9901, "status": "APPLIED"}` — 선착순 역할이면 `"status":"CONFIRMED"`.
오류 `422 ELIGIBILITY_FAILED` `{"errors":[{"code":"ATTACHMENT_REQUIRED"},{"code":"GUARDIAN_CONSENT_REQUIRED"}]}`, `409 ALREADY_APPLIED`.
> `accountNumber` 는 TLS 로 전송되어 서버가 즉시 암호화 저장한다. 기본 계좌를 쓰면 생략 가능(`useDefaultPayout:true`).

### D. 일괄 합격/예비/불합격 — `POST /company/applications/decide`
```json
{"applicationIds": [9901, 9902, 9903], "decision": "CONFIRM"}
```
`207`
```json
{"succeeded": [9901, 9902], "failed": [{"id": 9903, "code": "TIME_CONFLICT",
  "message": "같은 날 06:00~12:00 다른 확정 건과 시간이 겹칩니다"}]}
```

### E. 인원 정보표 — `GET /company/schedules/{id}/roster`
```json
{
  "scheduleId": 301, "shootDate": "2026-11-10",
  "items": [{
    "no": 1, "applicationId": 9901, "position": "정장 형사",
    "name": "홍길동", "phone": "010-1234-5678", "departureLocation": "사당역 1번 출구",
    "gender": "MALE", "birthYear": 1990, "heightCm": 178, "weightKg": 72, "topSize": "100", "bottomSize": "32", "shoeSize": 270,
    "bank": {"name": "국민은행", "account": "12345678901", "holder": "홍길동"},
    "departureConfirmedAt": "2026-11-09T09:12:00Z",
    "attendance": "PENDING", "checkinAt": null, "overtimeMinutes": 0,
    "pay": {"gross": 120000, "tax": 0, "net": 120000, "status": "PENDING"}
  }]
}
```
`bank.account` 는 OWNER/MANAGER 에게만 전체, 그 외 직책은 `12*******01` 형태. 호출은 `audit_logs`(`ROSTER_VIEW`)에 기록된다.

### F. 합격자 단체 메시지 — `POST /company/broadcasts`
```json
{"scheduleId": 301, "positionId": null, "targetFilter": "ALL_CONFIRMED",
 "title": "집합 장소 변경 안내", "body": "사당역 1번 출구 → 2번 출구로 변경되었습니다.", "channels": ["INAPP","PUSH"]}
```
`202` `{"broadcastId": 77, "recipientCount": 38}` — 발송은 아웃박스 워커가 비동기 처리. 조회: `GET /company/broadcasts?scheduleId=301` → `{id,title,recipientCount,readCount,createdAt}`.

### G. 출석 일괄 기록 — `PUT /company/schedules/{id}/attendances`
```json
{"items": [{"applicationId": 9901, "result": "ATTENDED", "checkinAt": "2026-11-10T06:55:00+09:00"},
           {"applicationId": 9902, "result": "NO_SHOW", "note": "연락 두절"}]}
```
`NO_SHOW` 는 성실도 원장에 `-noshow_penalty_points` 를 기록하고 `apply_blocked_until` 을 설정한다(`PGM-B08`).
