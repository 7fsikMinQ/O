import 'package:material_ui/material_ui.dart';

import '../core/format.dart';
import '../core/session.dart';
import '../core/ui/widgets.dart';
import 'apply.dart';

/// 일 곳 찾기: 목록·달력·검색. 모집 중인 공고만, 내가 차단한 업체는 빠진다.
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});
  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final _q = TextEditingController();
  DateTime _month = DateTime.now().toUtc().add(const Duration(hours: 9));
  String? _day;
  bool _calendar = false;
  int _gen = 0;

  @override
  Widget build(BuildContext context) {
    final s = SessionScope.of(context);
    final c = colorsOf(context);
    return AppPage(
      title: '🏠 일 곳 찾기',
      subtitle: '${_month.month}월 · 지원할 수 있는 공고',
      back: false,
      actions: [IconButton(tooltip: '알림', onPressed: () => Navigator.of(context).pushNamed('/notifications'), icon: Stack(clipBehavior: Clip.none, children: [const Text('🔔', style: TextStyle(fontSize: 22)), if (s.unread > 0) Positioned(right: -6, top: -4, child: Pill('${s.unread}', tone: Tone.bad))]))],
      children: [
        Row(children: [
          Expanded(child: SizedBox(height: 48, child: TextField(controller: _q, decoration: InputDecoration(hintText: '공고 제목 검색', filled: true, fillColor: c.surface, prefixIcon: const Icon(Icons.search), border: OutlineInputBorder(borderRadius: BorderRadius.circular(12), borderSide: BorderSide(color: c.line)), enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(12), borderSide: BorderSide(color: c.line))), textInputAction: TextInputAction.search, onSubmitted: (_) => setState(() => _gen++)))),
          const SizedBox(width: 8),
          SizedBox(width: 48, height: 48, child: IconButton.filledTonal(tooltip: _calendar ? '목록으로 보기' : '달력으로 보기', onPressed: () => setState(() => _calendar = !_calendar), icon: Text(_calendar ? '📋' : '📅', style: const TextStyle(fontSize: 20)))),
        ]),
        if (_calendar) _CalendarStrip(month: _month, selected: _day, onMonth: (m) => setState(() => _month = m), onPick: (d) => setState(() => _day = d == _day ? null : d), key: ValueKey('cal$_gen${_month.month}')),
        AsyncView<Map<String, dynamic>>(
          key: ValueKey('list$_gen$_day'),
          load: () => s.api.getMap('/jobs', query: {if (_q.text.trim().isNotEmpty) 'q': _q.text.trim(), if (_day != null) 'from': _day, if (_day != null) 'to': _day}),
          builder: (context, data, reload) {
            final items = (data['items'] as List).cast<Map<String, dynamic>>();
            if (items.isEmpty) return const EmptyNote('지금 모집 중인 공고가 없어요.\n나중에 다시 확인해 주세요 🍃');
            return Column(children: [
              for (final j in items) Padding(padding: const EdgeInsets.only(bottom: 12), child: JobCard(job: j)),
              AlertBox(data['timeNotice'] as String),
            ]);
          },
        ),
      ],
    );
  }
}

class _CalendarStrip extends StatelessWidget {
  const _CalendarStrip({super.key, required this.month, required this.selected, required this.onMonth, required this.onPick});
  final DateTime month;
  final String? selected;
  final ValueChanged<DateTime> onMonth;
  final ValueChanged<String> onPick;

  @override
  Widget build(BuildContext context) {
    final s = SessionScope.of(context);
    final c = colorsOf(context);
    return AsyncView<List<Map<String, dynamic>>>(
      load: () => s.api.getList('/jobs/calendar', query: {'month': monthKey(month)}),
      builder: (context, days, reload) {
        final counts = {for (final d in days) d['date'] as String: d['count'] as int};
        final first = DateTime(month.year, month.month, 1);
        final last = DateTime(month.year, month.month + 1, 0).day;
        final lead = first.weekday % 7;   // 일요일 시작
        final cells = <Widget>[for (var i = 0; i < lead; i++) const SizedBox()];
        for (var d = 1; d <= last; d++) {
          final key = '${month.year}-${two(month.month)}-${two(d)}';
          final n = counts[key] ?? 0;
          final on = selected == key;
          cells.add(InkWell(
            borderRadius: BorderRadius.circular(10),
            onTap: n == 0 ? null : () => onPick(key),
            child: Container(
              alignment: Alignment.center,
              decoration: BoxDecoration(color: on ? c.primary : (n > 0 ? c.primaryWeak : null), borderRadius: BorderRadius.circular(10)),
              child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
                Text('$d', style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600, color: on ? c.onPrimary : (n > 0 ? c.primaryText : c.text3))),
                if (n > 0) Text('$n건', style: TextStyle(fontSize: 12, color: on ? c.onPrimary : c.primaryText)),
              ]),
            ),
          ));
        }
        return AppCard(
          child: Column(children: [
            Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
              IconButton(tooltip: '지난달', onPressed: () => onMonth(DateTime(month.year, month.month - 1, 1)), icon: const Text('◀', style: TextStyle(fontSize: 18))),
              Text('${month.year}년 ${month.month}월', style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700)),
              IconButton(tooltip: '다음달', onPressed: () => onMonth(DateTime(month.year, month.month + 1, 1)), icon: const Text('▶', style: TextStyle(fontSize: 18))),
            ]),
            Row(children: [for (final w in ['일', '월', '화', '수', '목', '금', '토']) Expanded(child: Center(child: Text(w, style: TextStyle(fontSize: 13, color: c.text3))))]),
            const SizedBox(height: 4),
            GridView.count(crossAxisCount: 7, shrinkWrap: true, physics: const NeverScrollableScrollPhysics(), childAspectRatio: .85, children: cells),
          ]),
        );
      },
    );
  }
}

class JobCard extends StatelessWidget {
  const JobCard({super.key, required this.job});
  final Map<String, dynamic> job;
  @override
  Widget build(BuildContext context) {
    final c = colorsOf(context);
    final schedules = (job['schedules'] as List).cast<Map<String, dynamic>>();
    final first = schedules.first;
    final positions = schedules.expand((s) => (s['positions'] as List).cast<Map<String, dynamic>>()).toList();
    final best = positions.isEmpty ? null : positions.reduce((a, b) => (a['estimatedPayKrw'] as num) >= (b['estimatedPayKrw'] as num) ? a : b);
    final remaining = positions.fold<int>(0, (a, p) => a + (p['remaining'] as num).toInt());
    return AppCard(
      onTap: () => Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => JobDetailScreen(postingId: (job['id'] as num).toInt()))),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text('${job['title']}', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: c.text1)),
        Text('${job['companyName']}', style: TextStyle(fontSize: 14, color: c.text2)),
        const SizedBox(height: 8),
        Text('${fmtRange(first['startAt'] as String, first['endAt'] as String)} (예상)', style: TextStyle(fontSize: 15, color: c.text1)),
        if (first['regionName'] != null) Text('📍 ${first['regionName']}${schedules.length > 1 ? ' 외 ${schedules.length - 1}일정' : ''}', style: TextStyle(fontSize: 14, color: c.text2)),
        if (best != null) ...[
          const SizedBox(height: 8),
          Text('시급 ${won(best['hourlyWageKrw'] as num)}${best['wageMode'] == 'MIN_WAGE' ? ' (최저시급)' : ''}', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: c.primaryText)),
          Text('예상 ${won(best['estimatedPayKrw'] as num)}', style: TextStyle(fontSize: 13, color: c.text3)),
        ],
        const SizedBox(height: 8),
        Wrap(spacing: 6, runSpacing: 6, children: [
          Pill('남은 자리 $remaining명', tone: remaining > 0 ? Tone.ok : Tone.bad),
          if (job['isUrgent'] == true) const Pill('급해요 ⚡', tone: Tone.warn),
          if (schedules.any((s) => s['isLodging'] == true)) const Pill('숙박 🌙'),
        ]),
      ]),
    );
  }
}

/// 공고 상세: 비공개 현장은 합격 통보 뒤에만 촬영지·집합지가 보인다. 역할을 골라 지원한다.
class JobDetailScreen extends StatelessWidget {
  const JobDetailScreen({super.key, required this.postingId});
  final int postingId;

  @override
  Widget build(BuildContext context) {
    final s = SessionScope.of(context);
    final c = colorsOf(context);
    return LoadingPage<Map<String, dynamic>>(
      title: '🎬 공고 보기',
      load: () => s.api.getMap('/jobs/$postingId'),
      builder: (context, j, reload) {
        final schedules = (j['schedules'] as List).cast<Map<String, dynamic>>();
        return [
          Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('${j['title']}', style: TextStyle(fontSize: 22, fontWeight: FontWeight.w700, color: c.text1)),
            Text('${j['companyName']}', style: TextStyle(fontSize: 15, color: c.text2)),
          ]),
          AlertBox(j['timeNotice'] as String),
          if (j['description'] != null && '${j['description']}'.isNotEmpty) AppCard(child: Text('${j['description']}', style: const TextStyle(fontSize: 15, height: 1.5))),
          if (j['detailsHidden'] == true) const AlertBox('🔒 비공개 현장이에요. 합격 통보를 받으면 촬영지와 집합 장소가 보여요.'),
          for (final sc in schedules) _ScheduleCard(schedule: sc, jobOpen: j['status'] == 'OPEN', onApplied: reload),
        ];
      },
    );
  }
}

class _ScheduleCard extends StatelessWidget {
  const _ScheduleCard({required this.schedule, required this.jobOpen, required this.onApplied});
  final Map<String, dynamic> schedule;
  final bool jobOpen;
  final Future<void> Function() onApplied;

  @override
  Widget build(BuildContext context) {
    final c = colorsOf(context);
    final positions = (schedule['positions'] as List).cast<Map<String, dynamic>>();
    return AppCard(
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text('${fmtRange(schedule['startAt'] as String, schedule['endAt'] as String)} (예상)', style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700)),
        if (schedule['regionName'] != null) Text('📍 ${schedule['regionName']}${schedule['isLodging'] == true ? ' · 숙박' : ''}', style: TextStyle(fontSize: 14, color: c.text2)),
        if (schedule['meetingPlace'] != null) Text('모이는 곳 ${schedule['meetingPlace']}', style: TextStyle(fontSize: 14, color: c.text2)),
        for (final p in positions) ...[
          const SizedBox(height: 12),
          Divider(color: c.line, height: 1),
          const SizedBox(height: 12),
          Text('${p['title']} · ${p['headcount']}명', style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700)),
          const SizedBox(height: 4),
          Wrap(spacing: 6, runSpacing: 6, children: [
            if (p['genderReq'] != 'ANY') Pill(p['genderReq'] == 'MALE' ? '남자' : '여자'),
            if (p['birthYearMin'] != null || p['birthYearMax'] != null) Pill('${p['birthYearMin'] ?? ''}~${p['birthYearMax'] ?? ''}년생'),
            if (p['heightMin'] != null || p['heightMax'] != null) Pill('키 ${p['heightMin'] ?? ''}~${p['heightMax'] ?? ''}cm'),
            if (p['weightMin'] != null || p['weightMax'] != null) Pill('${p['weightMin'] ?? ''}~${p['weightMax'] ?? ''}kg'),
            if (p['tattooReq'] == 'NONE') const Pill('문신 없는 분'),
            if (p['hairLengths'] != null && (p['hairLengths'] as List).isNotEmpty) const Pill('머리 길이 조건'),
          ]),
          const SizedBox(height: 8),
          Text('시급 ${won(p['hourlyWageKrw'] as num)}${p['wageMode'] == 'MIN_WAGE' ? ' (최저시급)' : ''}', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: c.primaryText)),
          Text('예상 ${won(p['estimatedPayKrw'] as num)} (9시간 이내는 8시간 기준)', style: TextStyle(fontSize: 13, color: c.text3)),
          if (p['payNote'] != null) Text('${p['payNote']}', style: TextStyle(fontSize: 14, color: c.text2)),
          if (p['dressCode'] != null) Text('의상 ${p['dressCode']}', style: TextStyle(fontSize: 14, color: c.text2)),
          const SizedBox(height: 8),
          if (p['myApplication'] != null)
            AlertBox('내 지원: ${statusLabel((p['myApplication'] as Map)['status'] as String)}', tone: Tone.ok)
          else if (jobOpen && (p['remaining'] as num) > 0)
            PrimaryButton('지원하기 ✍️', onPressed: () async {
              final s = SessionScope.of(context);
              if (!s.loggedIn) return;
              final done = await Navigator.of(context).push<bool>(MaterialPageRoute<bool>(builder: (_) => ApplyFlow(positionId: (p['id'] as num).toInt(), roleTitle: '${p['title']}')));
              if (done == true) await onApplied();
            })
          else
            const AlertBox('이 역할은 지원이 끝났어요.', tone: Tone.bad),
        ],
      ]),
    );
  }
}
