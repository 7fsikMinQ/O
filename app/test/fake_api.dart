import 'package:bojohelp_app/core/api.dart';
import 'package:bojohelp_app/core/session.dart';

/// 서버 대신 미리 만든 응답을 돌려주는 가짜 API. 경로 앞부분으로 찾는다.
class FakeApi extends Api {
  FakeApi() : super(baseUrl: 'http://fake', store: MemoryTokenStore()) {
    access = 'token';
  }

  final List<String> calls = [];

  static const t0 = '2026-12-20T00:00:00Z';
  static const t1 = '2026-12-20T08:00:00Z';

  static Map<String, dynamic> position({int id = 11}) => {
        'id': id, 'title': '저잣거리 행인 (긴 역할 이름도 줄바꿈이 잘 되는지 보는 시험용 문구)', 'headcount': 5, 'remaining': 3, 'selectedCount': 1, 'confirmedCount': 1,
        'genderReq': 'MALE', 'birthYearMin': 1985, 'birthYearMax': 2002, 'heightMin': 165, 'heightMax': 185, 'weightMin': 55, 'weightMax': 85, 'hairLengths': ['SHORT'], 'tattooReq': 'NONE',
        'wageMode': 'MIN_WAGE', 'hourlyWageKrw': 10320, 'estimatedPayKrw': 82560, 'payNote': '식대 별도 지급, 교통비 없음', 'dressCode': '한복(상의 흰색)', 'requirements': null, 'status': 'OPEN', 'myApplication': null,
      };

  static Map<String, dynamic> schedule({int pid = 11}) => {
        'id': 7, 'startAt': t0, 'endAt': t1, 'startDate': '2026-12-20', 'endDate': '2026-12-20', 'isLodging': false, 'regionName': '서울특별시', 'meetingPlace': '강남역 1번 출구',
        'positions': [position(id: pid)],
      };

  static Map<String, dynamic> job({int id = 5}) => {
        'id': id, 'title': '가제 사극 드라마 (제목이 길어도 두 줄로 잘 넘어가야 해요)', 'companyName': '보출기획 주식회사', 'description': '저잣거리 행인 역할입니다. 사극 의상은 현장에서 맞춰요.', 'status': 'OPEN', 'isUrgent': true,
        'concealDetails': true, 'detailsHidden': true, 'firstShootDate': '2026-12-20', 'lastShootDate': '2026-12-20', 'timeNotice': '시간은 모두 예상이며 확정이 아니에요. 촬영 상황에 따라 바뀌거나 연장될 수 있어요.',
        'schedules': [schedule()],
      };

  static Map<String, dynamic> application({String status = 'SELECTED', int id = 21}) => {
        'id': id, 'status': status, 'postingId': 5, 'postingTitle': '가제 사극 드라마', 'companyName': '보출기획 주식회사', 'positionTitle': '저잣거리 행인', 'appliedAt': t0,
        'workStartAt': t0, 'workEndAt': t1, 'responseDueAt': '2026-12-19T03:00:00Z', 'confirmedAt': null, 'departureConfirmedAt': null, 'conflictOverride': false, 'message': '열심히 하겠습니다',
        'gatherPlace': '강남역 1번 출구', 'gatherAddress': '서울 강남구 강남대로 396', 'shootingLocation': '비밀 세트장', 'contactPhone': '010-1234-5678', 'dressCode': '한복', 'payNote': '식대 별도',
        'timeNotice': '시간은 모두 예상이며 확정이 아니에요.', 'conflicts': [], 'estimatedPayKrw': null, 'moved': null,
      };

  @override
  Future<dynamic> request(String method, String path, {Object? data, Map<String, dynamic>? query, bool retry = true}) async {
    calls.add('$method $path');
    final key = '$method $path';
    bool m(String p) => key.startsWith(p);
    if (m('GET /app/config')) return {'updateRequired': false, 'flags': {}, 'otpChannel': 'email'};
    if (m('GET /me/activity')) return {'confirmedCount': 12, 'attendedCount': 10, 'noshowCount': 1, 'penaltyCount': 0};
    if (key == 'GET /me') return {'id': 1, 'name': '김출연', 'roles': ['ACTOR'], 'phoneMasked': ''};
    if (m('GET /terms')) {
      return [
        for (final (i, t) in [('SERVICE', true), ('PRIVACY', true), ('THIRD_PARTY_PROVISION', true), ('SENSITIVE_ID', true), ('E_SIGNATURE', true), ('MARKETING', false)].indexed)
          {'id': i + 1, 'type': t.$1, 'version': '0.1', 'content': '[초안] 약관 내용 ${t.$1}', 'required': t.$2},
      ];
    }
    if (m('GET /jobs/calendar')) return [{'date': '2026-12-20', 'count': 2}, {'date': '2026-12-22', 'count': 1}];
    if (m('GET /jobs/')) return job();
    if (m('GET /jobs')) {
      return {'items': [{...job(), 'schedules': [schedule()]}, {...job(id: 6), 'title': '현대극 카페 손님', 'isUrgent': false}], 'nextCursor': null, 'timeNotice': '시간은 모두 예상이며 확정이 아니에요.'};
    }
    if (m('GET /positions/') && path.endsWith('/apply-form')) {
      return {'missing': [], 'reasons': [], 'eligible': true, 'profile': {'gender': 'MALE', 'birthDate': '1990-01-01', 'heightCm': 175, 'weightKg': 68, 'hairLength': 'SHORT', 'hasTattoo': false, 'defaultDepartureLocation': '강남역 1번 출구'}, 'hasPayoutAccount': true,
          'position': {'id': 11, 'title': '행인'}, 'eSignatureTerm': {'id': 5, 'version': '0.1', 'content': '[초안] 전자서명 및 증빙 보관'}, 'conflicts': [], 'alreadyApplied': false};
    }
    if (m('GET /master/banks')) return [{'code': '004', 'name': 'KB국민은행'}, {'code': '088', 'name': '신한은행'}];
    if (m('GET /me/applications') && path.endsWith('/offer')) {
      return {'title': '합격서', 'company': '보출기획 주식회사', 'posting': '가제 사극 드라마', 'role': '저잣거리 행인', 'name': '김출연', 'expectedGatherAt': t0, 'gatherToleranceMinutes': 120, 'expectedEndAt': t1, 'endToleranceMinutes': 240,
          'place': '강남역 1번 출구', 'address': '서울 강남구 강남대로 396', 'dressCode': '한복', 'notes': null, 'notices': ['시간은 모두 예상이며 확정이 아니에요.', '근로계약서는 현장에서 따로 작성해요. 이 합격서는 근로계약서가 아니에요.', '보수는 공고에 적힌 내용을 따르며, 이 문서에는 금액이 없어요.']};
    }
    if (m('GET /me/applications') && path.endsWith('/qr')) return {'token': '21.3.99999.abcdef', 'expiresInSeconds': 20, 'kind': 'CHECKIN', 'workDate': '2026-12-20'};
    if (m('GET /me/applications/')) return application(status: status);
    if (m('GET /me/applications')) return [application(), application(status: 'CONFIRMED', id: 22), application(status: 'APPLIED', id: 23), application(status: 'REJECTED', id: 24)];
    if (m('GET /me/conflicts')) {
      return [{'id': 1, 'detectedAt': t0, 'acknowledgedAt': null, 'postingA': '첫 일정', 'aStart': t0, 'aEnd': t1, 'postingB': '겹치는 일정', 'bStart': t0, 'bEnd': t1}];
    }
    if (m('GET /me/messages/threads/')) {
      return {'id': 1, 'subject': '집합 안내', 'companyId': 3, 'companyName': '보출기획 주식회사', 'blocked': false, 'messages': [
        {'id': 1, 'senderSide': 'COMPANY', 'body': '강남역 1번 출구에서 8시 50분까지 모여 주세요. 늦지 않게 와 주세요!', 'createdAt': t0},
        {'id': 2, 'senderSide': 'ACTOR', 'body': '네, 확인했어요.', 'createdAt': t0},
      ]};
    }
    if (m('GET /me/messages/threads')) return [{'id': 1, 'subject': '집합 안내', 'companyName': '보출기획 주식회사', 'lastBody': '강남역 1번 출구에서 8시 50분까지 모여 주세요.', 'lastMessageAt': t0, 'unread': 2}];
    if (m('GET /notifications/unread-count')) return {'count': 3};
    if (m('GET /notifications')) return [{'id': 1, 'type': 'SELECTED', 'title': '🎉 뽑혔어요! 확정해 주세요', 'body': '가제 사극 드라마 · 저잣거리 행인', 'createdAt': t0, 'readAt': null}];
    if (m('GET /me/actor-profile/tax-identity')) return {'registered': true, 'masked': '900101-1******'};
    if (m('GET /me/actor-profile/payout')) return {'bankCode': '004', 'bankName': 'KB국민은행', 'accountMasked': '****6789', 'holder': '김출연', 'holderMatchesName': true, 'defaultDepartureLocation': '강남역'};
    if (m('GET /me/actor-profile')) {
      return {'birthDate': '1990-01-01', 'gender': 'MALE', 'heightCm': 175, 'weightKg': 68, 'topSize': '100', 'bottomSize': '32', 'shoeSize': 270, 'hairLength': 'SHORT', 'hasTattoo': false, 'homeAddress': '서울 강남구 테헤란로 1',
          'homeAddressDetail': '101동', 'defaultDepartureLocation': '강남역', 'introduction': '성실합니다', 'hasPhone': false, 'hasTaxIdentity': false, 'hasPayoutAccount': true};
    }
    if (m('POST') || m('PUT') || m('DELETE')) return <String, dynamic>{};
    throw ApiException(404, const {});
  }

  String status = 'SELECTED';
}

Session fakeSession([FakeApi? api]) {
  final s = Session(api ?? FakeApi());
  s.me = {'id': 1, 'name': '김출연', 'roles': ['ACTOR']};
  s.ready = true;
  s.unread = 3;
  return s;
}
