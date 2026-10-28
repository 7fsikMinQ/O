/// 시간은 서버가 UTC, 화면은 한국 시간(KST). 금액은 원 정수.
DateTime kst(String iso) => DateTime.parse(iso).toUtc().add(const Duration(hours: 9));

const _wd = ['월', '화', '수', '목', '금', '토', '일'];

String two(int n) => n.toString().padLeft(2, '0');

String fmtDate(String iso) {
  final d = kst(iso);
  return '${d.month}월 ${d.day}일 (${_wd[d.weekday - 1]})';
}

String fmtTime(String iso) {
  final d = kst(iso);
  return '${two(d.hour)}:${two(d.minute)}';
}

String fmtDateTime(String iso) => '${fmtDate(iso)} ${fmtTime(iso)}';

/// "12월 20일 (토) 09:00 ~ 17:00" — 여러 날이면 끝 날짜도 보여 준다.
String fmtRange(String startIso, String endIso) {
  final a = kst(startIso), b = kst(endIso);
  final sameDay = a.year == b.year && a.month == b.month && a.day == b.day;
  return sameDay ? '${fmtDate(startIso)} ${fmtTime(startIso)} ~ ${fmtTime(endIso)}' : '${fmtDateTime(startIso)} ~ ${fmtDateTime(endIso)}';
}

String won(num? n) {
  if (n == null) return '';
  final s = n.round().toString();
  final buf = StringBuffer();
  for (var i = 0; i < s.length; i++) {
    if (i > 0 && (s.length - i) % 3 == 0) buf.write(',');
    buf.write(s[i]);
  }
  return '$buf원';
}

String monthKey(DateTime d) => '${d.year}-${two(d.month)}';

String phoneDisplay(String? p) {
  if (p == null) return '';
  final d = p.replaceAll(RegExp(r'\D'), '');
  if (d.length == 11) return '${d.substring(0, 3)}-${d.substring(3, 7)}-${d.substring(7)}';
  return p;
}

/// 지원·합격 상태를 쉬운 말로(19번 문서). 시스템 용어를 그대로 쓰지 않는다.
String statusLabel(String s) => switch (s) {
      'APPLIED' => '지원했어요',
      'SELECTED' => '뽑혔어요 · 확정해 주세요',
      'CONFIRMED' => '참여 확정',
      'REJECTED' || 'AUTO_REJECTED' => '이번엔 함께하지 못해요',
      'CANCELED_BY_ACTOR' => '내가 취소했어요',
      'CANCELED_BY_COMPANY' => '업체 사정으로 취소',
      'CANCELED_BY_SYSTEM' => '일정이 연기됐어요',
      'EXPIRED' => '응답 시간이 지났어요',
      _ => s,
    };
