import 'dart:async';

import 'package:material_ui/material_ui.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:url_launcher/url_launcher.dart';

import '../core/api.dart';
import '../core/format.dart';
import '../core/session.dart';
import '../core/ui/widgets.dart';

Tone _tone(String s) => switch (s) {
      'CONFIRMED' => Tone.ok,
      'SELECTED' => Tone.warn,
      'APPLIED' => Tone.normal,
      _ => Tone.bad,
    };

/// 내 일정: 지원·합격 목록과 달력식 보기. 뽑힌 건은 맨 위에서 확정을 기다린다.
class MyScheduleScreen extends StatefulWidget {
  const MyScheduleScreen({super.key});
  @override
  State<MyScheduleScreen> createState() => _MyScheduleScreenState();
}

class _MyScheduleScreenState extends State<MyScheduleScreen> {
  int _gen = 0;
  String _filter = 'ACTIVE';

  @override
  Widget build(BuildContext context) {
    final s = SessionScope.of(context);
    final c = colorsOf(context);
    return AppPage(
      title: '📅 내 일정',
      back: false,
      onRefresh: () async => setState(() => _gen++),
      actions: [IconButton(tooltip: '일정 겹침', onPressed: () => Navigator.of(context).push<void>(MaterialPageRoute<void>(builder: (_) => const ConflictsScreen())), icon: const Text('⚠️', style: TextStyle(fontSize: 20)))],
      children: [
        Wrap(spacing: 8, children: [
          for (final f in const [('ACTIVE', '진행 중'), ('DONE', '지난 일'), ('', '전부')])
            ChoiceChip(label: Text(f.$2), selected: _filter == f.$1, onSelected: (_) => setState(() => _filter = f.$1)),
        ]),
        AsyncView<List<Map<String, dynamic>>>(
          key: ValueKey('apps$_gen$_filter'),
          load: () => s.api.getList('/me/applications'),
          builder: (context, apps, reload) {
            final active = {'APPLIED', 'SELECTED', 'CONFIRMED'};
            final list = apps.where((a) => _filter.isEmpty || (_filter == 'ACTIVE' ? active.contains(a['status']) : !active.contains(a['status']))).toList();
            if (list.isEmpty) return const EmptyNote('아직 지원한 일이 없어요.\n홈에서 일을 찾아 지원해 보세요 🎬');
            list.sort((a, b) => ((a['status'] == 'SELECTED' ? 0 : 1) - (b['status'] == 'SELECTED' ? 0 : 1)));
            return Column(children: [
              for (final a in list)
                Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: AppCard(
                    tone: a['status'] == 'SELECTED' ? c.warnBg : null,
                    onTap: () async {
                      await Navigator.of(context).push<void>(MaterialPageRoute<void>(builder: (_) => ApplicationDetailScreen(applicationId: (a['id'] as num).toInt())));
                      await reload();
                    },
                    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Row(children: [Expanded(child: Text('${a['postingTitle']}', style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700))), const SizedBox(width: 8), Flexible(child: Pill(statusLabel(a['status'] as String), tone: _tone(a['status'] as String)))]),
                      Text('${a['companyName']} · ${a['positionTitle']}', style: TextStyle(fontSize: 14, color: c.text2)),
                      const SizedBox(height: 6),
                      Text('${fmtRange(a['workStartAt'] as String, a['workEndAt'] as String)} (예상)', style: const TextStyle(fontSize: 15)),
                      if (a['status'] == 'SELECTED' && a['responseDueAt'] != null) Text('⏰ ${fmtDateTime(a['responseDueAt'] as String)} 까지 확정해 주세요', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: c.warnText)),
                      if (a['conflictOverride'] == true) const Padding(padding: EdgeInsets.only(top: 6), child: Pill('다른 일과 시간이 가까워요', tone: Tone.warn)),
                    ]),
                  ),
                ),
            ]);
          },
        ),
      ],
    );
  }
}

/// 지원/합격 상세. 합격 통보 뒤에는 모이는 곳·연락처·지도·QR 이 보이고, 확정·취소·참석 확인을 한다.
class ApplicationDetailScreen extends StatefulWidget {
  const ApplicationDetailScreen({super.key, required this.applicationId});
  final int applicationId;
  @override
  State<ApplicationDetailScreen> createState() => _ApplicationDetailScreenState();
}

class _ApplicationDetailScreenState extends State<ApplicationDetailScreen> {
  bool _busy = false;
  int _gen = 0;

  Future<void> _confirm(Session s, {bool ack = false}) async {
    setState(() => _busy = true);
    try {
      await s.api.post('/applications/${widget.applicationId}/confirm', {'acknowledgeConflict': ack});
      if (mounted) {
        toast(context, '참여가 확정되었어요 🎉');
        setState(() => _gen++);
      }
    } on ApiException catch (e) {
      if (!mounted) return;
      if (e.code == 'SCHEDULE_CONFLICT') {
        final go = await showModalBottomSheet<bool>(context: context, isScrollControlled: true, builder: (_) => _ConflictSheet(conflicts: (e.body['conflicts'] as List).cast<Map<String, dynamic>>()));
        if (go == true && mounted) await _confirm(s, ack: true);
      } else {
        infoDialog(context, '확정할 수 없어요', e.message);
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _cancel(Session s, Map<String, dynamic> a) async {
    final confirmed = a['status'] == 'CONFIRMED';
    String? reason;
    if (confirmed) {
      final ctl = TextEditingController();
      final ok = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('확정한 일을 취소할까요?'),
          content: Column(mainAxisSize: MainAxisSize.min, children: [
            const Text('업체 스케줄러가 사유를 보고 안 나온 횟수와는 별도로 취소 패널티를 정할 수 있어요. 사유를 적어 주세요.', style: TextStyle(fontSize: 14, height: 1.5)),
            const SizedBox(height: 8),
            TextField(controller: ctl, maxLines: 2, decoration: const InputDecoration(hintText: '예) 몸살로 움직일 수 없어요')),
          ]),
          actions: [TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('그대로 둘게요')), TextButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('취소할게요'))],
        ),
      );
      if (ok != true || ctl.text.trim().isEmpty) return;
      reason = ctl.text.trim();
    } else if (!await confirmDialog(context, a['status'] == 'SELECTED' ? '이번 일은 못 하겠어요' : '지원을 취소할까요?', '취소해도 불이익은 없어요.', yes: '취소할게요', no: '그대로 둘게요')) {
      return;
    }
    try {
      await s.api.delete('/applications/${widget.applicationId}', query: {'reason': ?reason});
      if (mounted) {
        toast(context, '취소했어요');
        Navigator.of(context).pop();
      }
    } catch (e) {
      if (mounted) toast(context, errorText(e), bad: true);
    }
  }

  Future<void> _departure(Session s) async {
    try {
      await s.api.post('/applications/${widget.applicationId}/departure-confirm');
      if (!mounted) return;
      toast(context, '참석을 확인했어요');
      setState(() => _gen++);
    } catch (e) {
      if (!mounted) return;
      infoDialog(context, '지금은 누를 수 없어요', errorText(e));
    }
  }

  Future<void> _open(String url) async {
    final ok = await launchUrl(Uri.parse(url), mode: LaunchMode.externalApplication);
    if (!ok && mounted) toast(context, '열 수 없어요', bad: true);
  }

  @override
  Widget build(BuildContext context) {
    final s = SessionScope.of(context);
    final c = colorsOf(context);
    return LoadingPage<Map<String, dynamic>>(
      key: ValueKey('detail$_gen'),
      title: '🎬 내 지원',
      load: () => s.api.getMap('/me/applications/${widget.applicationId}'),
      bottomBuilder: (context, a, reload) {
        final st = a['status'] as String;
        if (st == 'SELECTED') {
          return Column(mainAxisSize: MainAxisSize.min, children: [PrimaryButton('✅ 참여할게요', busy: _busy, onPressed: () => _confirm(s)), const SizedBox(height: 8), LineButton('이번엔 못 해요', bad: true, onPressed: () => _cancel(s, a))]);
        }
        if (st == 'CONFIRMED') return LineButton('확정 취소하기', bad: true, onPressed: () => _cancel(s, a));
        if (st == 'APPLIED') return LineButton('지원 취소하기', bad: true, onPressed: () => _cancel(s, a));
        return null;
      },
      builder: (context, a, reload) {
        final st = a['status'] as String;
        final revealed = st == 'SELECTED' || st == 'CONFIRMED';
        return [
          if (st == 'SELECTED') AppCard(tone: c.warnBg, child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [const Text('🎉 뽑혔어요!', style: TextStyle(fontSize: 22, fontWeight: FontWeight.w800)), const SizedBox(height: 4), Text('${fmtDateTime(a['responseDueAt'] as String)} 까지 "참여할게요"를 눌러야 확정돼요.', style: TextStyle(fontSize: 15, color: c.warnText, fontWeight: FontWeight.w600))])),
          if (st == 'CONFIRMED') const AlertBox('참여가 확정되었어요 ✅', tone: Tone.ok),
          if (st == 'CANCELED_BY_SYSTEM') const AlertBox('일정이 연기되어 새 일정으로 옮겨졌어요. 새 일정에서 다시 확정해 주세요. 불이익은 없어요.'),
          AppCard(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [Expanded(child: Text('${a['postingTitle']}', style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700))), const SizedBox(width: 8), Flexible(child: Pill(statusLabel(st), tone: _tone(st)))]),
              Text('${a['companyName']} · ${a['positionTitle']}', style: TextStyle(fontSize: 14, color: c.text2)),
              const SizedBox(height: 8),
              KeyValue('모이는 때', '${fmtDateTime(a['workStartAt'] as String)} (예상)'),
              KeyValue('끝나는 때', '${fmtDateTime(a['workEndAt'] as String)} (예상)'),
              if (revealed && a['gatherPlace'] != null) KeyValue('모이는 곳', '${a['gatherPlace']}'),
              if (revealed && a['gatherAddress'] != null) KeyValue('주소', '${a['gatherAddress']}'),
              if (revealed && a['shootingLocation'] != null) KeyValue('촬영지', '${a['shootingLocation']}'),
              if (a['dressCode'] != null) KeyValue('의상', '${a['dressCode']}'),
              if (a['payNote'] != null) KeyValue('비고', '${a['payNote']}'),
            ]),
          ),
          AlertBox('${a['timeNotice']}'),
          if (revealed) ...[
            if (a['gatherAddress'] != null || a['gatherPlace'] != null)
              LineButton('📍 지도에서 열기', onPressed: () => _open('https://map.kakao.com/?q=${Uri.encodeComponent('${a['gatherAddress'] ?? a['gatherPlace']}')}')),
            if (a['contactPhone'] != null) LineButton('📞 반장님께 전화', onPressed: () => _open('tel:${a['contactPhone']}')),
            LineButton('📄 합격서 보기', onPressed: () => Navigator.of(context).push<void>(MaterialPageRoute<void>(builder: (_) => OfferScreen(applicationId: widget.applicationId)))),
          ],
          if (st == 'CONFIRMED') ...[
            if (a['departureConfirmedAt'] == null)
              LineButton('⏰ 참석 확인 (모이기 2시간 전~30분 전)', onPressed: () => _departure(s))
            else
              const AlertBox('참석 확인을 했어요 ✅', tone: Tone.ok),
            PrimaryButton('📲 출근·퇴근 QR 보기', onPressed: () => Navigator.of(context).push<void>(MaterialPageRoute<void>(builder: (_) => QrScreen(applicationId: widget.applicationId)))),
          ],
          if ((a['conflicts'] as List).isNotEmpty) const AlertBox('이 일과 시간이 가까운 다른 일이 있어요. "일정 겹침" 화면에서 확인해 주세요.'),
          if (a['message'] != null) AppCard(child: KeyValue('내가 쓴 말', '${a['message']}')),
          LineButton('내 지원서 증빙 보기', onPressed: () async {
            final d = await s.api.getMap('/applications/${widget.applicationId}/document');
            if (context.mounted) infoDialog(context, '지원서 증빙', '서명 ${fmtDateTime(d['signedAt'] as String)}\n약관 v${d['signedTermsVersion']}\n변조 확인값 ${'${d['snapshotHash']}'.substring(0, 16)}…\n\n지원서와 서명은 5년간 보관돼요.');
          }),
        ];
      },
    );
  }
}

class _ConflictSheet extends StatelessWidget {
  const _ConflictSheet({required this.conflicts});
  final List<Map<String, dynamic>> conflicts;
  @override
  Widget build(BuildContext context) {
    final c = colorsOf(context);
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          const Text('⚠️ 시간이 겹치거나 가까워요', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800)),
          const SizedBox(height: 8),
          for (final x in conflicts) Padding(padding: const EdgeInsets.only(bottom: 8), child: AppCard(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text('${x['postingTitle']} · ${x['companyName']}', style: const TextStyle(fontWeight: FontWeight.w700)), Text(fmtRange(x['workStartAt'] as String, x['workEndAt'] as String), style: TextStyle(color: c.text2))]))),
          const AlertBox('두 곳 모두 확정하면 양쪽 업체에 알림이 가고, 확인할 때까지 1시간마다 다시 알려 드려요.'),
          const SizedBox(height: 12),
          PrimaryButton('취소하고 다른 곳을 고를게요', onPressed: () => Navigator.pop(context, false)),
          const SizedBox(height: 8),
          LineButton('그래도 둘 다 확정할게요', bad: true, onPressed: () => Navigator.pop(context, true)),
        ]),
      ),
    );
  }
}

/// 일정이 겹치는 경고를 보고 "확인"을 누르면 반복 알림이 멈춘다.
class ConflictsScreen extends StatelessWidget {
  const ConflictsScreen({super.key});
  @override
  Widget build(BuildContext context) {
    final s = SessionScope.of(context);
    return LoadingPage<List<Map<String, dynamic>>>(
      title: '⚠️ 일정 겹침',
      subtitle: '알고 둘 다 확정한 일이에요. 확인을 누르면 알림이 멈춰요.',
      load: () => s.api.getList('/me/conflicts'),
      builder: (context, list, reload) => [
        if (list.isEmpty) const EmptyNote('겹치는 일정이 없어요 👍'),
        for (final x in list)
          AppCard(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('${x['postingA']}', style: const TextStyle(fontWeight: FontWeight.w700)),
              Text(fmtRange(x['aStart'] as String, x['aEnd'] as String)),
              const SizedBox(height: 8),
              Text('${x['postingB']}', style: const TextStyle(fontWeight: FontWeight.w700)),
              Text(fmtRange(x['bStart'] as String, x['bEnd'] as String)),
              const SizedBox(height: 12),
              x['acknowledgedAt'] == null
                  ? PrimaryButton('확인했어요', onPressed: () async {
                      await s.api.post('/me/conflicts/${x['id']}/ack');
                      await reload();
                    })
                  : const Pill('확인했어요 ✅', tone: Tone.ok),
            ]),
          ),
      ],
    );
  }
}

/// 합격서: 금액이 없고 시간은 예상. 근로계약서가 아니라는 안내가 항상 들어간다.
class OfferScreen extends StatelessWidget {
  const OfferScreen({super.key, required this.applicationId});
  final int applicationId;
  @override
  Widget build(BuildContext context) {
    final s = SessionScope.of(context);
    return LoadingPage<Map<String, dynamic>>(
      title: '📄 합격서',
      subtitle: '${DateTime.now().toUtc().add(const Duration(hours: 9)).year}년 합격 안내',
      load: () => s.api.getMap('/me/applications/$applicationId/offer'),
      builder: (context, o, _) => [
        AppCard(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            KeyValue('업체', '${o['company']}'),
            KeyValue('공고', '${o['posting']}'),
            KeyValue('역할', '${o['role']}'),
            KeyValue('이름', '${o['name']}'),
            KeyValue('모이는 때', '${fmtDateTime(o['expectedGatherAt'] as String)} (±${(o['gatherToleranceMinutes'] as num) ~/ 60}시간 예상)'),
            KeyValue('끝나는 때', '${fmtDateTime(o['expectedEndAt'] as String)} (±${(o['endToleranceMinutes'] as num) ~/ 60}시간 예상)'),
            if (o['place'] != null) KeyValue('모이는 곳', '${o['place']}'),
            if (o['address'] != null) KeyValue('주소', '${o['address']}'),
            if (o['dressCode'] != null) KeyValue('의상', '${o['dressCode']}'),
          ]),
        ),
        for (final n in (o['notices'] as List)) AlertBox('$n'),
      ],
    );
  }
}

/// 출근·퇴근 QR: 현장 담당자가 열어야 나타나고, 30초마다 바뀐다(대리 출석 방지).
class QrScreen extends StatefulWidget {
  const QrScreen({super.key, required this.applicationId});
  final int applicationId;
  @override
  State<QrScreen> createState() => _QrScreenState();
}

class _QrScreenState extends State<QrScreen> {
  Map<String, dynamic>? _qr;
  String? _err;
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    _fetch();
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  Future<void> _fetch() async {
    final s = SessionScope.read(context);
    try {
      final q = await s.api.getMap('/me/applications/${widget.applicationId}/qr');
      if (!mounted) return;
      setState(() {
        _qr = q;
        _err = null;
      });
      _timer?.cancel();
      _timer = Timer(Duration(seconds: ((q['expiresInSeconds'] as num).toInt() - 1).clamp(3, 30)), _fetch);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _qr = null;
        _err = errorText(e);
      });
      _timer?.cancel();
      _timer = Timer(const Duration(seconds: 10), _fetch);
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = colorsOf(context);
    final kind = _qr?['kind'] == 'CHECKOUT' ? '퇴근' : '출근';
    return AppPage(
      title: '📲 $kind 확인 QR',
      subtitle: '현장 담당자에게 이 화면을 보여 주세요.',
      children: [
        if (_qr != null)
          AppCard(
            child: Column(children: [
              Container(color: const Color(0xFFFFFFFF), padding: const EdgeInsets.all(12), child: QrImageView(data: _qr!['token'] as String, size: 220, backgroundColor: const Color(0xFFFFFFFF))),
              const SizedBox(height: 8),
              const Pill('30초마다 바뀌어요 ⏱', tone: Tone.ok),
            ]),
          )
        else
          AlertBox(_err ?? '불러오는 중이에요…'),
        Text('도움이 필요하면 현장 담당자에게 말해 주세요.', style: TextStyle(fontSize: 13, color: c.text3)),
      ],
    );
  }
}
