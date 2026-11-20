# 개발 제안서 (PDF)

- 결과물: `OIO_캐스팅_플랫폼_구축_제안서.pdf` (A4, 약 53쪽, 책갈피 포함)
- 구성: 요약 → 배경·목표 → 서비스 한눈에 → 단계별 범위 → 기능 명세 → 직급·권한 → **화면 설계 72개** → 추가 개발 기능 → 보안·법적 → 기술·완성 자산 → 일정·검수 → 비용 → 사업성 → 위험 → 계약 조건 → 확인 사항 → 부록(용어·권한 코드·화면 목록·출처)
- `proposal.html`: PDF의 원본 HTML(브라우저로도 볼 수 있음). 폰트는 `fonts/`(Pretendard, SIL OFL).

## 내용을 고쳐 다시 만들기
1. 제안자·날짜·개발비 금액: `tools/proposal/config.json` 수정 (금액을 `null` 로 두면 `[금액 입력]` 표시, 숫자를 넣으면 합계까지 자동 계산).
2. 본문: `tools/proposal/build.py`·`content.py`, 화면: `tools/proposal/screens_*.py`.
3. 생성: `python3 tools/proposal/make.py` (필요: Python `pypdf`, Node `playwright`, `poppler-utils`)
4. 검증: `python3 tools/proposal/verify.py` — 41개 항목(분석 10·검토 11·최종 10·문서/소스 대조 10)이 모두 ✔ 여야 한다.

개발비 금액과 제안자 연락처는 제안자가 입력하는 항목이며, 법률·세무 자문이 아니다.
