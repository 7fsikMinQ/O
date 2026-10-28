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


총 **180**개 엔드포인트. 권한 약어: PUB 비로그인 / USER 로그인 / ACTOR 출연자 / CMP 승인 업체 구성원 / CMP-M 업체 OWNER·MANAGER / CMP-F 업체 OWNER·MANAGER·담당 FIELD_LEADER / OPR 운영자 이상 / ADM 관리자.

## 인증·계정
| ID | 메서드 | 경로 | 이름 | 권한 | 요청/응답 요약 | 테이블 | 주요 오류 | 구현 기능 |
|---|---|---|---|---|---|---|---|---|
| API-AU01 | POST | `/auth/email/signup` | 업체·관리자 이메일 가입 | PUB | {email,password,name,phone?,type:ACTOR\|COMPANY,consents[]} → 201 {userId} | users,user_roles,user_consents | 409 중복이메일/휴대폰, 422 필수약관 미동의 | F-AU01, F-AU03, F-AU04 |
| API-AU02 | POST | `/auth/email/login` | 업체·관리자 이메일 로그인 | PUB | {email,password,deviceId} → {accessToken,refreshToken(앱)/쿠키(웹),roles} | users,refresh_tokens,login_histories | 401, 423 정지계정, 429 | F-AU01 |
| API-AU03 | POST | `/auth/social/{provider}` | 소셜 로그인 | PUB | {idToken\|code} → 토큰 또는 {needSignup:true,signupToken} | user_auth_providers,users | 401 | F-AU02 |
| API-AU04 | POST | `/auth/social/{provider}/signup` | 소셜 가입 완료 | PUB | {signupToken,type,phone,consents[]} → 토큰 | users,user_auth_providers,user_roles,user_consents | 409, 422 | F-AU02 |
| API-AU05 | POST | `/auth/refresh` | 토큰 갱신(회전) | PUB | {refreshToken} → 새 토큰쌍 | refresh_tokens | 401 재사용 감지 시 전체 폐기 | F-AU08 |
| API-AU06 | POST | `/auth/logout` | 로그아웃 | USER | {refreshToken?, all?:bool} → 204 | refresh_tokens,device_tokens |  | F-AU08 |
| API-AU07 | POST | `/auth/password/reset-request` | 임시 비밀번호 메일 요청 | PUB | {email} → 202(항상, 계정 유무 숨김) | users,password_resets,outbox_events | 이메일당 1시간 3회·IP당 10회 초과 시 조용히 미발송 | F-AU05 |
| API-AU08 | PUT | `/me/password` | 비밀번호 변경(임시 비밀번호로 로그인한 제한 토큰 또는 현재 비밀번호 확인) | USER | {currentPassword?,newPassword} → 200 새 토큰(모든 기기 로그아웃) | users,password_resets,refresh_tokens,outbox_events | 400 규칙, 403 현재 비밀번호 불일치 | F-AU05 |
| API-AU09 | POST | `/auth/phone/send` | 휴대폰 인증번호 발송 | USER | {phone} → 202 | users | 409 중복번호, 429 | F-AU07 |
| API-AU10 | POST | `/auth/phone/verify` | 휴대폰 인증 확인 | USER | {phone,code} → 204 | users | 400 | F-AU07 |
| API-AU11 | DELETE | `/me` | 회원 탈퇴 | USER | {password?,reason} → 204 | users,applications | 409 진행 중 확정 건 | F-AU06, F-AU11 |
| API-AU12 | GET | `/terms` | 약관 목록(최신) | PUB | → [{type,version,content,isRequired}] | terms |  | F-AU04 |
| API-AU13 | POST | `/auth/otp/send` | 문자 인증번호 발송(가입·로그인) | PUB | {phone,purpose} → 202 | otp_challenges | 429 재전송 제한 | F-AU10 |
| API-AU14 | POST | `/auth/otp/verify` | 인증번호 확인 → 로그인/가입 진행 | PUB | {phone,code,deviceId} → 토큰 또는 {needSignup:true,signupToken} | otp_challenges,users,refresh_tokens | 400 틀림, 429 시도 초과 | F-AU10 |
| API-AU15 | POST | `/public/account-deletion-requests` | 웹에서 탈퇴 요청(로그인 없이) | PUB | {phone\|email} → 202 (문자/메일 본인 확인 후 처리) | account_deletion_requests,outbox_events | 항상 202(계정 존재 비노출) | F-AU11 |
| API-AU16 | PUT | `/me/blocks/companies/{companyId}` | 업체 차단(DELETE 로 해제) | ACTOR | → 204 | actor_company_blocks |  | F-AU12 |
| API-AU17 | POST | `/auth/signup` | 휴대폰 가입 완료(인증 직후) | PUB | {signupToken,name,agreedTermIds[],deviceId?} → 201 {accessToken,expiresIn,refreshToken} | users,user_roles,user_consents,terms,refresh_tokens | 400 필수 약관 누락, 401 가입 토큰 만료, 409 이미 가입한 번호 | F-AU10 |
| API-AU18 | POST | `/auth/email/verify` | 이메일 확인 링크 처리 | PUB | {token} → 204 | email_verifications,users | 400 만료·사용됨 | F-AU13 |
| API-AU19 | POST | `/auth/email/resend-verification` | 확인 메일 재발송 | USER | → 202 | email_verifications,outbox_events | 409 이미 확인, 429 1분 1회·하루 5회 | F-AU13 |

## 내 정보·프로필·설정
| ID | 메서드 | 경로 | 이름 | 권한 | 요청/응답 요약 | 테이블 | 주요 오류 | 구현 기능 |
|---|---|---|---|---|---|---|---|---|
| API-ME01 | GET | `/me` | 내 정보 | USER | → {user,roles,companies[],actorProfileCompleted} | users,user_roles,company_members |  | F-AU03 |
| API-ME02 | GET | `/me/actor-profile` | 내 출연자 프로필 | ACTOR | → 프로필+사진+통계 | actor_profiles,file_attachments |  | F-PR01 |
| API-ME03 | PUT | `/me/actor-profile` | 프로필 저장 | ACTOR | {birthDate,gender,heightCm,weightKg,topSize,bottomSize,shoeSize,hairLength,hairStyle,hasTattoo,canDrive,hasVehicle,regionId,homePostalCode,homeAddress,homeAddressDetail,tags[],intro} | actor_profiles | 422 | F-PR01 |
| API-ME04 | PUT | `/me/actor-profile/payout` | 기본 계좌·집합지 저장 | ACTOR | {bankCode,accountNumber,holder,defaultDepartureLocation} (서버 암호화) | actor_payout_accounts,actor_profiles | 422 | F-PR03 |
| API-ME05 | GET | `/me/actor-profile/payout` | 기본 정산 정보 조회(계좌 마스킹) | ACTOR | → {bankName,accountMasked,holder} | actor_payout_accounts |  | F-PR03 |
| API-ME06 | PUT | `/me/actor-profile/disclosure-consent` | 이력 공개 동의 변경 | ACTOR | {agree:bool} → 204 | actor_profiles,user_consents,audit_logs |  | F-PR06 |
| API-ME07 | GET | `/me/activity` | 내 활동 이력(횟수) | ACTOR | → {confirmed,attended,noshow,penalty} | actor_profiles,actor_penalty_events,applications |  | F-PR05 |
| API-ME08 | POST | `/me/guardian-consents` | 법정대리인 동의서 제출 | ACTOR | {guardianName,guardianPhone,relationship,fileIds[]} → 201 | guardian_consents,file_attachments | 422 파일누락 | F-PR07 |
| API-ME09 | GET | `/me/guardian-consents` | 동의서 상태 조회 | ACTOR | → [{status,validUntil,rejectReason}] | guardian_consents |  | F-PR07 |
| API-ME10 | PUT | `/me/notification-settings` | 알림 수신 설정 | USER | {items:[{type,channel,enabled}]} | notification_settings |  | F-NT04 |
| API-ME11 | GET | `/me/notification-settings` | 알림 수신 설정 조회 | USER | → items[] | notification_settings |  | F-NT04 |
| API-ME12 | POST | `/me/devices` | 푸시 토큰 등록 | USER | {platform,token,isPwaInstalled,permission:GRANTED\|DENIED\|DEFAULT,userAgent} (등록·갱신) | device_tokens |  | F-NT02, F-NT09 |
| API-ME13 | DELETE | `/me/devices/{token}` | 푸시 토큰 해제 | USER |  | device_tokens |  | F-NT02 |
| API-ME21 | GET | `/me/push-status` | 푸시 가능 여부 진단 | USER | → {canPush, reasons[NOT_INSTALLED,PERMISSION_DENIED,NO_TOKEN,UNSUPPORTED], fallbackChannels[]} | device_tokens,notification_settings |  | F-NT09 |
| API-ME14 | PUT | `/me/actor-profile/tax-identity` | 주민등록번호 등록·교체 | ACTOR | {rrn,termVersion} (쓰기 전용, 서버 암호화+해시, 기존 버전 비활성) → {masked} | actor_tax_identities,user_consents,audit_logs | 409 이미 등록된 번호(중복 가입), 422 형식/생년월일·성별 불일치 | F-PR04 |
| API-ME15 | GET | `/me/actor-profile/tax-identity` | 주민번호 등록 상태 | ACTOR | → {registered, masked:'900101-1******'} | actor_tax_identities |  | F-PR04 |
| API-ME16 | GET | `/me/messages/threads` | 내 메시지함 | ACTOR | ?cursor → 업체가 보낸 스레드 목록 | message_threads,messages |  | F-OP04 |
| API-ME17 | GET | `/me/messages/threads/{id}` | 대화 보기 | ACTOR | → 메시지 목록(읽음 처리) | message_threads,messages |  | F-OP04 |
| API-ME18 | POST | `/me/messages/threads/{id}/reply` | 답장 | ACTOR | {body} (업체가 시작한 스레드에만) | messages,notifications | 403 스레드 없음 | F-OP04 |
| API-ME19 | GET | `/me/conflicts` | 내 일정 충돌 목록 | ACTOR | → 미확인 충돌 | schedule_conflicts,applications |  | F-SL09 |
| API-ME20 | POST | `/me/conflicts/{id}/ack` | 충돌 경고 확인 | ACTOR | {keepBoth\|cancelApplicationId} → 재알림 중지 | schedule_conflicts,applications,notifications |  | F-SL09 |

## 파일
| ID | 메서드 | 경로 | 이름 | 권한 | 요청/응답 요약 | 테이블 | 주요 오류 | 구현 기능 |
|---|---|---|---|---|---|---|---|---|
| API-FL01 | POST | `/files/presign` | 업로드 URL 발급 | USER | {filename,contentType,size,purpose} → {fileId,uploadUrl,expiresAt} | files | 422 타입/용량 초과 | F-PR02, F-SY08 |
| API-FL02 | POST | `/files/{fileId}/complete` | 업로드 완료 확인 | USER | → 200 (존재·크기·MIME 검증 후 ACTIVE) | files | 409 미업로드 | F-PR02, F-SY08 |
| API-FL03 | GET | `/files/{fileId}/url` | 서명 조회 URL | USER | → {url,expiresAt} (권한 검사) | files,file_attachments | 403 | F-PR02 |
| API-FL04 | DELETE | `/files/{fileId}` | 파일 삭제 | USER | → 204 (소유/권한) | files,file_attachments | 403 | F-PR02 |
| API-FL05 | POST | `/me/actor-profile/photos` | 프로필 사진 업로드(서버 검증·EXIF 제거·축소) | ACTOR | multipart {file,kind:PROFILE\|FULL_BODY} → 201 {id,kind,width,height,url} | files,file_attachments | 400 형식·크기, 409 종류당 5장 초과, 413 10MB 초과 | F-PR02 |
| API-FL06 | GET | `/me/actor-profile/photos` | 내 사진 목록(서명 URL 포함) | ACTOR | → [{id,kind,width,height,url}] | files,file_attachments |  | F-PR02 |
| API-FL07 | DELETE | `/me/actor-profile/photos/{id}` | 내 사진 삭제 | ACTOR | → 204 | files,file_attachments | 404 남의 사진 | F-PR02 |
| API-FL08 | GET | `/files/{publicId}` | 서명 URL 로 사진 내려받기(개발 로컬 저장소) | PUB | ?exp&sig → image/jpeg (비공개, no-store) | files | 403 만료·변조, 404 | F-PR02 |

## 업체
| ID | 메서드 | 경로 | 이름 | 권한 | 요청/응답 요약 | 테이블 | 주요 오류 | 구현 기능 |
|---|---|---|---|---|---|---|---|---|
| API-CO01 | POST | `/companies` | 업체 등록 | USER | {name,bizRegNo,representative,phone,address,licenseFileId} → 201 PENDING | companies,company_members,company_verifications,file_attachments | 409 사업자번호 중복 | F-CO01 |
| API-CO02 | GET | `/companies/{id}` | 업체 프로필 조회 | USER | → 공개 프로필 | companies |  | F-CO04 |
| API-CO03 | PATCH | `/companies/{id}` | 업체 프로필 수정 | CMP-M |  | companies |  | F-CO04 |
| API-CO04 | GET | `/companies/{id}/members` | 직원 목록 | CMP-M |  | company_members |  | F-CO03 |
| API-CO05 | POST | `/companies/{id}/members` | 직원 초대 | CMP-M | {email,memberRole,departmentName} | company_members,outbox_events | 404, 409 | F-CO03 |
| API-CO06 | PATCH | `/companies/{id}/members/{userId}` | 직책 변경/비활성 | CMP-M | {memberRole?,status?} | company_members | 403 OWNER 보호 | F-CO03, F-CO07 |
| API-CO07 | GET | `/company/blocks` | 차단 목록 | CMP-M |  | company_actor_blocks |  | F-CO05 |
| API-CO08 | PUT | `/company/blocks/{actorUserId}` | 출연자 차단 | CMP-M | {reason} | company_actor_blocks |  | F-CO05 |
| API-CO09 | DELETE | `/company/blocks/{actorUserId}` | 차단 해제 | CMP-M |  | company_actor_blocks |  | F-CO05 |
| API-CO10 | POST | `/companies/{id}/license` | 사업자등록증 업로드(PDF·JPG·PNG, 검토 건당 3개) | CMP-M | multipart {file} → 201 {id,contentType} | files,file_attachments,company_verifications | 400 형식, 409 3개 초과·검토 중 아님, 404 구성원 아님 | F-CO01 |
| API-CO11 | POST | `/companies/{id}/resubmit` | 반려된 업체 재신청(대표만) | CMP-M | → 200 {id,status:PENDING,verificationId} | companies,company_verifications,audit_logs | 409 반려 상태 아님 | F-CO01 |
| API-CO12 | GET | `/me/companies` | 내 업체 목록(직책·상태) | USER | → [{companyId,name,status,memberRole,memberStatus}] | companies,company_members |  | F-CO01 |
| API-CO13 | GET | `/me/company-invitations` | 받은 직원 초대 목록 | USER | → [{companyId,companyName,memberRole}] | company_members,companies |  | F-CO03 |
| API-CO14 | POST | `/me/company-invitations/{companyId}/accept` | 직원 초대 수락(COMPANY 역할 부여) | USER | → 204 | company_members,user_roles | 404 초대 없음 | F-CO03 |
| API-CO15 | POST | `/me/company-invitations/{companyId}/decline` | 직원 초대 거절 | USER | → 204 | company_members | 404 초대 없음 | F-CO03 |

## 공고 관리(업체)
| ID | 메서드 | 경로 | 이름 | 권한 | 요청/응답 요약 | 테이블 | 주요 오류 | 구현 기능 |
|---|---|---|---|---|---|---|---|---|
| API-JB01 | POST | `/company/jobs` | 공고 생성(임시저장) | CMP | {title(가제),productionId?,concealDetails=true,isUrgent,contactPhone,applyDeadlineAt,schedules[{startAt,endAt,isLodging,regionId,shootingLocation,meetingPlace,meetingAddress,fieldLeaderUserId,positions[{title,headcount,genderReq,birthYearMin/Max,heightMin/Max,weightMin/Max,hairLengths[],tattooReq,wageMode:MIN_WAGE\|CUSTOM,hourlyWageKrw?,payNote,dressCode,requirements,startAt?,endAt?,meetingPlace?}]}]} → 201 | job_postings,job_schedules,job_positions,minimum_wages,productions | 422 일정 1시간~21일 위반 · 최저시급 연도 미등록 · BELOW_MIN_WAGE(직접 입력 시급이 법정 최저 미만) | F-JB01, F-JB11 |
| API-JB02 | PUT | `/company/jobs/{id}` | 공고 수정 | CMP | 전체 교체(지원자 있는 역할의 정원 감소·삭제는 거부) | job_postings,job_schedules,job_positions | 409 지원자 존재 | F-CO06, F-JB01 |
| API-JB03 | POST | `/company/jobs/{id}/publish` | 공고 게시 | CMP | → OPEN (승인 업체만) | job_postings,outbox_events | 403 미승인 업체, 422 필수 누락 | F-JB04 |
| API-JB04 | POST | `/company/jobs/{id}/copy` | 공고 복사 | CMP | {shiftDays\|dates[]} → 새 DRAFT | job_postings,job_schedules,job_positions |  | F-JB02 |
| API-JB05 | POST | `/company/jobs/{id}/close` | 공고 마감 | CMP | → CLOSED + 자동 불합격 처리 결과 {autoRejected:n} | job_postings,job_positions,applications,application_status_histories,outbox_events |  | F-JB04, F-SL05 |
| API-JB06 | POST | `/company/jobs/{id}/cancel` | 공고 취소 | CMP-M | {reason} → 전체 지원자 알림 | job_postings,applications,outbox_events |  | F-JB04 |
| API-JB07 | PATCH | `/company/schedules/{id}/status` | 일정 마감/취소 | CMP | {status:CLOSED\|CANCELED,reason} | job_schedules,applications,outbox_events |  | F-JB05 |
| API-JB08 | POST | `/company/positions/{id}/close` | 역할 마감 | CMP | → 해당 역할 자동 불합격 | job_positions,applications |  | F-JB04 |
| API-JB09 | GET | `/company/jobs` | 내 업체 공고 목록 | CMP | ?status&page → 커서 페이징 | job_postings |  | F-JB04, F-JB10 |
| API-JB10 | GET | `/company/jobs/{id}` | 내 공고 상세(편집용) | CMP |  | job_postings,job_schedules,job_positions |  | F-JB01, F-JB10 |
| API-JB11 | GET | `/company/dashboard` | 업체 대시보드 집계 | CMP | → 오늘/내일 현장, 미처리 지원자 수, 미응답자 수 | job_schedules,applications |  | F-JB07 |
| API-JB12 | POST | `/company/jobs/{id}/reopen` | 마감 해제(정원 여유 시) | CMP |  | job_postings,job_positions |  | F-JB04 |

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
| API-AP01 | GET | `/positions/{id}/apply-form` | 지원 폼 사전정보 | ACTOR | → 자동 입력값(이름·번호·성별·출생연도·키·몸무게·사이즈·집주소·집합지·계좌 마스킹), 주민번호 등록 여부, 자격 판정 사유, 최소 사진 수, 서명 약관, 겹침 경고 | actor_profiles,actor_payout_accounts,actor_tax_identities,applications,terms |  | F-AU09, F-AP01 |
| API-AP02 | POST | `/positions/{id}/applications` | 지원 | ACTOR | {departureLocation,payout:{useDefault\|bankCode,accountNumber,holder},message,photoFileIds[≥1],signatureFileId,signedTermsVersion} (Idempotency-Key) → 201 {applicationId,status:APPLIED}. 서버가 프로필 스냅샷·무결성 해시·IP/기기 기록 | applications,actor_tax_identities,file_attachments,application_status_histories,outbox_events | 403 차단/미성년/주민번호 미등록, 409 중복, 422 자격·사진·서명 | F-AU09, F-AP02 |
| API-AP03 | DELETE | `/applications/{id}` | 지원/확정 취소 | ACTOR | {reason?} (확정 후 취소는 reason 필수) → 204. 확정 후 취소 시 스케줄러 판정 대기 생성 | applications,cancellation_reviews,outbox_events | 409 | F-AP03 |
| API-AP04 | GET | `/me/applications` | 내 지원 목록 | ACTOR | ?status&from&to | applications,job_postings |  | F-AP04 |
| API-AP05 | GET | `/me/applications/{id}` | 내 지원/합격 상세 | ACTOR | → 합격 시 비공개 정보·반장 연락처·공지 | applications,job_schedules,job_broadcasts |  | F-AP04, F-OP15 |
| API-AP06 | GET | `/me/calendar` | 내 일정 달력 | ACTOR | ?month → [{date,status[]}] | applications |  | F-SR06 |
| API-AP07 | POST | `/applications/{id}/departure-confirm` | 참석 확인 | ACTOR | → 204 (집합 2시간 전~30분 전에만) | applications | 409 기간 외 | F-AP05 |
| API-AP08 | POST | `/applications/{id}/confirm` | 출연자 확정(수락) | ACTOR | {proceedDespiteConflict?:bool} → 200 CONFIRMED. 겹침이 있으면 409 TIME_CONFLICT + 충돌 목록, 명시적으로 진행하면 conflict_override·충돌 추적·양쪽 업체 알림 | applications,job_positions,schedule_conflicts,notifications,outbox_events | 409 TIME_CONFLICT/EXPIRED/FULL | F-AP06 |
| API-AP09 | GET | `/me/applications/{id}/qr` | 출퇴근 개인 QR 토큰 | ACTOR | → {token,expiresIn} (열린 세션이 있을 때만, 수초마다 재발급) | attendance_sessions,applications | 409 세션 없음 | F-OP06 |
| API-AP10 | GET | `/applications/{id}/document` | 지원서 증빙 사본 | ACTOR | → PDF (본인) / 권한 업체 | applications,file_attachments | 403 | F-AP07 |

## 선발(업체)
| ID | 메서드 | 경로 | 이름 | 권한 | 요청/응답 요약 | 테이블 | 주요 오류 | 구현 기능 |
|---|---|---|---|---|---|---|---|---|
| API-SL01 | GET | `/company/positions/{id}/applications` | 역할별 지원자 목록 | CMP | ?status&sort&view=card\|grid → 휴대폰 공개, 집주소/계좌/주민번호는 합격 통보 이후 권한자만 | applications,actor_profiles |  | F-SL01 |
| API-SL02 | GET | `/company/applications/{id}` | 지원자 상세 | CMP | → 프로필·사진·시간겹침 배지(합격 후 연락처 공개) | applications,actor_profiles,file_attachments |  | F-SL02, F-OP15 |
| API-SL03 | GET | `/company/actors/{userId}/history` | 지원자 역대 합격·출석 이력 | CMP | 동의한 출연자만, 최근 50건 | applications,attendances,actor_profiles | 403 동의 철회 | F-SL02 |
| API-SL04 | GET | `/company/actors/{userId}/applications` | 지원자 타 공고 지원 목록 | CMP | ?date → 타 업체 공고는 제목 마스킹, 시간대·상태 중심 | applications,job_schedules,company_subscriptions,plan_features,app_settings | 403 구독 필요 | F-SL03, F-SY09 |
| API-SL05 | POST | `/company/applications/decide` | 일괄 합격 통보·불합격 | CMP | {applicationIds[],decision:SELECT\|REJECT} (사유 없음) → 207 {succeeded[],failed[{id,code}],warnings[{id,code:OVERLAPS_OTHER}]}. SELECT 는 정원(selected+confirmed<headcount) 원자 증가·예약 구간 기록·확정 기한 설정 | applications,job_positions,application_status_histories,outbox_events,audit_logs | 207 부분 성공, 409 FULL | F-SL04, F-SL08 |
| API-SL06 | POST | `/company/applications/{id}/cancel` | 합격 취소(업체) | CMP-M | {} → 출연자 알림, penalty_exempt | applications,job_positions,outbox_events |  | F-SL06 |
| API-SL08 | GET | `/company/jobs/{id}/reserve-candidates` | 마감 후 추가 합격 후보 | CMP | ?scheduleId → 지원자 중 일정 ±1일에 다른 합격/확정이 없는 사람 | applications,job_schedules,job_positions |  | F-SL07 |

## 합격 이후·현장
| ID | 메서드 | 경로 | 이름 | 권한 | 요청/응답 요약 | 테이블 | 주요 오류 | 구현 기능 |
|---|---|---|---|---|---|---|---|---|
| API-OP01 | GET | `/company/schedules/{id}/headcount` | 인원표 | CMP | → 역할별 집계 | v_schedule_headcount_summary |  | F-OP01 |
| API-OP02 | GET | `/company/schedules/{id}/roster` | 인원 정보표 | CMP-F | ?position&q → 이름·번호·성별·출생연도·키·몸무게·사이즈·집주소·집합지·계좌(권한자)·주민번호(항상 마스킹) | v_schedule_confirmed_roster,audit_logs |  | F-OP02 |
| API-OP03 | GET | `/company/schedules/{id}/roster.xlsx` | 인원 정보표 엑셀(암호화) | CMP-M | ?template&includeRrn → 비밀번호로 보호된 xlsx (비밀번호는 별도 1회 표시), 감사로그 | v_schedule_confirmed_roster,actor_tax_identities,export_templates,audit_logs,plan_features | 403 권한/플랜 | F-OP03, F-SY09 |
| API-OP04 | POST | `/company/broadcasts` | 단체 알림/메시지 발송 | CMP-F | {scheduleId\|positionId,targetFilter:ALL_SELECTED\|ALL_CONFIRMED\|DEPARTURE_UNCONFIRMED\|CLOSED_AVAILABLE\|CUSTOM,applicationIds?[],title,body,channels[]} → 202 {broadcastId,recipientCount}. 수신자별 스레드 생성 | job_broadcasts,job_broadcast_recipients,message_threads,messages,notifications,outbox_events,plan_features,usage_counters | 429 월 한도 | F-OP04, F-SY09 |
| API-OP05 | GET | `/company/broadcasts` | 발송 내역 | CMP-F | ?postingId → 수신/읽음/답장 수 | job_broadcasts,job_broadcast_recipients,messages |  | F-OP04 |
| API-OP06 | GET | `/company/schedules/{id}/departure-pending` | 참석 미응답자 목록 | CMP-F | → 이름·전화 | applications |  | F-OP05 |
| API-OP07 | PUT | `/company/schedules/{id}/attendances` | 수기 출석/노쇼 보정 | CMP-F | {items:[{applicationId,workDate,result,checkinAt?,checkoutAt?,note?}]} (멱등, 감사) | attendances,actor_penalty_events,audit_logs | 403 담당 아님 | F-OP07 |
| API-OP08 | PUT | `/company/schedules/{id}/checkout` | 퇴근·연장 일괄 입력 | CMP-F | {applicationIds[],checkoutAt} | attendances |  | F-OP07 |
| API-OP09 | PUT | `/company/applications/{id}/settlement` | 정산 기록 저장 | CMP-M | {items:[{type,amount,quantity,memo}],status} | settlements,settlement_items |  | F-OP11 |
| API-OP11 | POST | `/applications/{id}/reviews` | 평가 작성 | USER | {score,comment} | reviews | 409 중복/미출석 | F-OP13 |
| API-OP14 | POST | `/company/messages` | 개별/선택 메시지 시작 | CMP-F | {applicationIds[],title,body} → 스레드 생성 | message_threads,messages,notifications,outbox_events |  | F-OP04 |
| API-OP15 | GET | `/company/messages/threads` | 업체 메시지함 | CMP-F | ?postingId&unread | message_threads,messages |  | F-OP04 |
| API-OP16 | GET | `/company/messages/threads/{id}` | 대화 보기 | CMP-F |  | message_threads,messages |  | F-OP04 |
| API-OP17 | POST | `/company/messages/threads/{id}/reply` | 업체 답장 | CMP-F | {body} | messages,notifications |  | F-OP04 |
| API-OP18 | GET | `/company/cancellation-reviews` | 취소 패널티 판정 대기 | CMP | → SCHEDULER 에게만 대기 건 노출 | cancellation_reviews,applications |  | F-OP09 |
| API-OP19 | POST | `/company/cancellation-reviews/{applicationId}/vote` | 패널티 판정 투표 | CMP | {vote:PENALTY\|NO_PENALTY} (SCHEDULER 만). 한 명이라도 PENALTY → 공고당 1건 부과 | cancellation_review_votes,cancellation_reviews,actor_penalty_events,actor_profiles,audit_logs | 403 SCHEDULER 아님 | F-OP09 |
| API-OP20 | POST | `/company/schedules/{id}/attendance-sessions` | 출근/퇴근 세션 열기 | CMP-F | {kind:CHECKIN\|CHECKOUT,workDate} → 출연자 앱에 QR 활성화 | attendance_sessions | 403 담당 아님, 409 이미 열림 | F-OP06 |
| API-OP21 | POST | `/company/attendance-sessions/{id}/close` | 세션 닫기 | CMP-F |  | attendance_sessions |  | F-OP06 |
| API-OP22 | POST | `/company/attendance/scan` | QR 스캔 출퇴근 기록 | CMP-F | {sessionId,token} → {applicationId,name,result} (멱등) | attendances,attendance_sessions,applications | 409 만료/타 세션 토큰 | F-OP06 |
| API-OP23 | GET | `/company/schedules/{id}/settlements/bank-transfer.xlsx` | 은행 대량이체 엑셀 | CMP-M | ?template → xlsx | settlements,settlement_items,export_templates,audit_logs |  | F-OP12 |
| API-OP24 | GET | `/company/schedules/{id}/settlements/withholding.xlsx` | 원천징수 자료 엑셀(암호화) | CMP-M | → 주민번호 포함, 비밀번호 보호 | settlements,actor_tax_identities,export_templates,audit_logs | 403 RRN_REVEAL 권한 | F-OP12 |
| API-OP25 | POST | `/company/schedules/{id}/postpone` | 일정 연기(자동 이동) | CMP | {newStartAt,newEndAt,reason} → 새 일정 생성, 합격자는 SELECTED 상태로 이동(출연자가 확정해야 인원 충족), 안내 알림 | job_schedules,job_positions,applications,application_status_histories,outbox_events |  | F-JB06 |
| API-OP26 | POST | `/company/applications/{id}/reveal-rrn` | 주민번호 단건 열람 | CMP-M | → {rrn} (사유 입력, 감사로그, 합격 통보 이후만) | actor_tax_identities,audit_logs | 403 | F-OP02 |
| API-OP27 | GET | `/me/applications/{id}/offer` | 합격서 조회·PDF(금액 없음) | ACTOR | → 합격서 JSON, ?format=pdf → 서명 URL | applications,files,file_attachments | 합격 통보(SELECTED) 이후만; 업체는 /company/applications/{id}/offer | F-OP17 |
| API-OP28 | POST | `/company/payout-batches` | 이체 배치 생성(SETTLEMENT/BANK_TRANSFER_FILE 플래그) | CMP-M | {scheduleId,templateCode,settlementIds[]} | payout_batches,payout_batch_items,settlements | 플래그 OFF 시 404, 예금주 불일치 경고 포함 | F-OP18 |
| API-OP29 | GET | `/company/payout-batches/{id}/file.xlsx` | 이체 파일 다운로드 | CMP-M | → 암호화 xlsx(계좌 복호화는 이 순간만) | payout_batches,export_templates,audit_logs |  | F-OP18 |
| API-OP30 | POST | `/company/payout-batches/{id}/confirm-paid` | 업체의 지급 완료 확인 기록 | CMP-M | {paidAt,reference} | payout_batches,settlements,audit_logs | 플랫폼은 송금하지 않으며 기록만 한다 | F-OP18 |
| API-OP31 | GET | `/me/applications/{id}/directions` | 길찾기·전화 링크 | ACTOR | → {meetingPlace,mapLinks:{naver,kakao},tel:{leader,company}} | applications,job_schedules | 403 합격(SELECTED/CONFIRMED) 전 | F-OP20 |
| API-OP32 | GET | `/company/broadcasts/{id}/receipts` | 공지 수신·읽음 현황 | CMP-M | → [{applicationId,name,deliveredAt,readAt,reminderCount}] | job_broadcast_recipients,job_broadcasts |  | F-OP21 |
| API-OP33 | POST | `/company/broadcasts/{id}/remind-unread` | 미읽음 재안내 | CMP-M | {channel:INAPP\|SMS} → {sent} | job_broadcast_recipients,outbox_events,audit_logs | 409 재안내 5회 초과, SMS 는 플래그·옵트인 필요 | F-OP21 |
| API-OP34 | GET | `/company/starred-actors` | ⭐ 출연자 목록 | CMP-M | → [{actorUserId,name,note,lastWorkedAt}] | company_starred_actors,actor_profiles |  | F-OP22 |
| API-OP35 | PUT | `/company/starred-actors/{actorUserId}` | ⭐ 저장·메모 수정(DELETE 로 해제) | CMP-M | {note?} → 204 | company_starred_actors,plan_features | 409 한도 초과, 403 같이 일한 적 없음 | F-OP22 |
| API-OP36 | PUT | `/me/settings/star-alerts` | ⭐ 알림 받기 켜기/끄기 | ACTOR | {enabled} → 204 | actor_profiles |  | F-OP22 |

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
| API-AD17 | GET | `/admin/guardian-consents` | 동의서 검수 대기 | OPR |  | guardian_consents |  | F-PR07 |
| API-AD18 | POST | `/admin/guardian-consents/{id}/verify` | 동의서 승인/반려 | OPR | {approve,rejectReason?} | guardian_consents,actor_profiles,audit_logs,outbox_events |  | F-PR07 |
| API-AD19 | PUT | `/admin/plans/{id}` | 요금제·기능 편집 | ADM | {features[]} | subscription_plans,plan_features,audit_logs |  | F-AD08 |
| API-AD20 | GET | `/admin/stats` | 운영 통계 | ADM | ?from&to | applications,job_postings,attendances |  | F-AD07 |
| API-AD21 | POST | `/admin/productions/{id}/merge` | 작품 병합 | ADM | {intoId} | productions,job_postings |  | F-AD04 |
| API-AD22 | POST | `/admin/inquiries/{postId}/answer` | 문의 답변 | OPR | {content} | comments,posts,notifications |  | F-BD02 |
| API-AD23 | PUT | `/admin/minimum-wages/{year}` | 최저시급 등록·수정 | ADM | {hourlyKrw,effectiveFrom,sourceNote} | minimum_wages,audit_logs |  | F-AD09 |
| API-AD24 | PUT | `/admin/export-templates/{code}` | 엑셀 양식 편집 | ADM | {name,kind,bankCode,columns[]} | export_templates,audit_logs |  | F-AD10 |
| API-AD25 | PUT | `/admin/feature-flags/{code}` | 기능 플래그 변경 | ADM | {enabled,confirmLegal:true} | feature_flags,audit_logs | 403 FLAG_MANAGE 없음, 법무 확인 필요 항목은 confirmLegal 필수 | F-SY13 |
| API-AD26 | GET | `/admin/companies/{id}` | 업체 신청 상세(사업자등록증 서명 URL, 열람 감사) | ADM | → 업체 정보 + licenses[{url,contentType}] | companies,files,audit_logs | 404 | F-CO02 |

## 마스터
| ID | 메서드 | 경로 | 이름 | 권한 | 요청/응답 요약 | 테이블 | 주요 오류 | 구현 기능 |
|---|---|---|---|---|---|---|---|---|
| API-MS01 | GET | `/master/regions` | 지역 | PUB |  | regions |  | F-JB01 |
| API-MS02 | GET | `/master/position-categories` | 역할 유형 | PUB |  | position_categories |  | F-JB01 |
| API-MS03 | GET | `/master/productions` | 작품 검색 | CMP | ?q → 자동완성 | productions |  | F-JB01 |
| API-MS04 | GET | `/master/banks` | 은행 코드 | USER |  |  |  | F-PR03 |
| API-MS05 | GET | `/master/minimum-wage` | 연도별 최저시급 | CMP | ?date=2027-01-05 → {year,hourlyKrw} (근무 시작일 기준 연도) | minimum_wages |  | F-JB09, F-JB11 |
| API-MS06 | GET | `/features` | 공개 기능 플래그 목록(클라이언트 메뉴 숨김용) | PUB | → {code:enabled} (법무 메모 제외) | feature_flags |  | F-SY13 |
| API-MS07 | GET | `/app/config` | 앱 설정(최소 버전·기능 플래그) | PUB | ?platform=android&version=1.0.3 → {updateRequired,flags} | app_settings,feature_flags |  | F-SY16 |

## 부록. 핵심 API 상세 예시

### A. 달력 집계 — `GET /jobs/calendar?month=2026-11&regionIds=11,41`
```json
{
  "month": "2026-11",
  "days": [
    {"date": "2026-11-10", "postings": 4, "positions": 9, "hasUrgent": true},
    {"date": "2026-11-11", "postings": 3, "positions": 7, "hasUrgent": false}
  ]
}
```
다일 일정(2박3일 등)은 걸친 모든 날짜에 집계된다(`start_date ≤ 날짜 ≤ end_date`). `OPEN` 공고·일정·역할만 포함.

### B. 지원 폼 사전정보 — `GET /positions/{id}/apply-form`
```json
{
  "eligible": false,
  "reasons": ["WEIGHT_OUT_OF_RANGE", "PHOTO_REQUIRED"],
  "prefill": {"name": "홍길동", "phone": "010-1234-5678", "gender": "MALE", "birthYear": 1990, "heightCm": 178, "weightKg": 72,
              "sizes": {"top": "100", "bottom": "32", "shoe": 270}, "homeAddress": "서울 강남구 …",
              "departureLocation": "사당역 1번 출구", "payout": {"bankName": "국민은행", "accountMasked": "123-****-**89", "holder": "홍길동"},
              "rrnRegistered": true},
  "required": {"minPhotos": 1, "signature": true, "signatureTerms": {"version": "1", "text": "…"}},
  "warnings": [{"code": "OVERLAPS_MY_APPLICATION", "applicationId": 9812, "range": "2026-11-10T06:00~12:00+09:00"}]
}
```
`warnings` 는 경고일 뿐 지원은 가능하다. `reasons` 가 비어 있어야 `eligible=true`.

### C. 지원 — `POST /positions/{id}/applications`  (헤더 `Idempotency-Key`)
```json
{
  "departureLocation": "사당역 1번 출구",
  "payout": {"useDefault": false, "bankCode": "004", "accountNumber": "12345678901", "holder": "홍길동"},
  "message": "검정 정장 보유",
  "photoFileIds": ["b1f6c0de-0000-0000-0000-000000000001"],
  "signatureFileId": "b1f6c0de-0000-0000-0000-000000000002",
  "signedTermsVersion": "1"
}
```
응답 `201` `{"applicationId": 9901, "status": "APPLIED"}`. 서버는 프로필을 **스냅샷**으로 저장하고, 서명 해시(`snapshot_hash`)·IP·기기·약관 버전을 기록한다. 오류 `422 ELIGIBILITY_FAILED` + 사유 코드 목록, `403 RRN_REQUIRED`.

### D. 합격 통보·불합격 일괄 — `POST /company/applications/decide`
```json
{"applicationIds": [9901, 9902, 9903], "decision": "SELECT"}
```
`207`
```json
{"succeeded": [9901, 9902],
 "failed": [{"id": 9903, "code": "FULL", "message": "정원이 모두 찼습니다"}],
 "warnings": [{"id": 9902, "code": "OVERLAPS_OTHER", "message": "같은 시간대 다른 합격 건이 있어요"}]}
```
`REJECT` 는 사유 없이 처리. `SELECT` 는 출연자의 확정을 기다리는 상태이며 정원 중 `selected_count` 가 증가한다.

### E. 출연자 확정 — `POST /applications/{id}/confirm`
충돌이 없으면 `200 {"status":"CONFIRMED"}`. 충돌이 있으면:
```json
{"status": 409, "code": "TIME_CONFLICT",
 "conflicts": [{"applicationId": 9812, "title": "현대극 B(가제)", "range": "2026-11-10T09:00~14:00+09:00"}]}
```
앱이 경고 시트를 보여 주고, 사용자가 "그래도 둘 다 확정"을 고르면 다시 호출한다.
```json
{"proceedDespiteConflict": true}
```
→ `200 {"status":"CONFIRMED","conflictId":15}` 양쪽 업체에 알림/메시지, 출연자에게 확인(ack) 요구 알림(1시간 간격).

### F. 인원 정보표 — `GET /company/schedules/{id}/roster`
```json
{
  "scheduleId": 301, "startAt": "2026-11-10T06:00:00+09:00",
  "items": [{
    "no": 1, "applicationId": 9901, "status": "CONFIRMED", "position": "정장 형사",
    "name": "홍길동", "phone": "010-1234-5678", "gender": "MALE", "birthYear": 1990,
    "heightCm": 178, "weightKg": 72, "sizes": "100/32/270",
    "homeAddress": "서울 강남구 …", "departureLocation": "사당역 1번 출구",
    "bank": {"name": "국민은행", "account": "12345678901", "holder": "홍길동"},
    "rrn": "900101-1******",
    "departureConfirmedAt": "2026-11-10T04:10:00Z", "attendance": "PENDING",
    "pay": {"gross": 82560, "status": "PENDING"}
  }]
}
```
`bank.account` 는 OWNER/MANAGER 에게만 전체, 그 외에는 `12*******01`. 주민번호는 **항상 마스킹**, 전체는 암호화 엑셀(`roster.xlsx?includeRrn=true`) 또는 단건 열람 API(사유·감사). 호출은 `audit_logs` 에 남는다.

### G. 메시지 — `POST /company/broadcasts`
```json
{"scheduleId": 301, "targetFilter": "CLOSED_AVAILABLE",
 "title": "추가 모집 문의", "body": "11/10 06시 촬영 가능하신가요? 가능하시면 답장 주세요.", "channels": ["INAPP","PUSH"]}
```
`202` `{"broadcastId": 77, "recipientCount": 12}`. 수신자마다 스레드가 생기고 출연자는 **답장만** 가능(`POST /me/messages/threads/{id}/reply`). `targetFilter` 는 `ALL_SELECTED | ALL_CONFIRMED | DEPARTURE_UNCONFIRMED | CLOSED_AVAILABLE | CUSTOM(applicationIds)`.

### H. QR 출퇴근
1) 반장: `POST /company/schedules/301/attendance-sessions` `{"kind":"CHECKIN","workDate":"2026-11-10"}` → `201 {"sessionId":55}`
2) 출연자 앱: `GET /me/applications/9901/qr` → `{"token":"…","expiresIn":30}` (세션이 닫혀 있으면 `409 NO_OPEN_SESSION`)
3) 반장 앱: `POST /company/attendance/scan` `{"sessionId":55,"token":"…"}` → `{"applicationId":9901,"name":"홍길동","result":"ATTENDED"}`
4) 반장: `POST /company/attendance-sessions/55/close`

### I. 취소 패널티 투표 — `POST /company/cancellation-reviews/{applicationId}/vote`
```json
{"vote": "PENALTY"}
```
SCHEDULER 만 호출 가능. 한 명이라도 `PENALTY` 면 해당 공고에 대해 패널티 1건이 반영된다(중복 투표는 무시).
