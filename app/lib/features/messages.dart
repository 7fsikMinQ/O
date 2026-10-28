import 'package:material_ui/material_ui.dart';

import '../core/api.dart';
import '../core/format.dart';
import '../core/session.dart';
import '../core/ui/widgets.dart';

/// 메시지함: 업체가 먼저 보내고 나는 답장만 할 수 있어요.
class MessagesScreen extends StatefulWidget {
  const MessagesScreen({super.key});
  @override
  State<MessagesScreen> createState() => _MessagesScreenState();
}

class _MessagesScreenState extends State<MessagesScreen> {
  int _gen = 0;
  @override
  Widget build(BuildContext context) {
    final s = SessionScope.of(context);
    final c = colorsOf(context);
    return AppPage(
      title: '💬 메시지',
      back: false,
      subtitle: '업체가 보낸 안내예요. 답장만 할 수 있어요.',
      onRefresh: () async => setState(() => _gen++),
      children: [
        AsyncView<List<Map<String, dynamic>>>(
          key: ValueKey('threads$_gen'),
          load: () => s.api.getList('/me/messages/threads'),
          builder: (context, list, reload) {
            if (list.isEmpty) return const EmptyNote('받은 메시지가 없어요.');
            return Column(children: [
              for (final t in list)
                Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: AppCard(
                    onTap: () async {
                      await Navigator.of(context).push<void>(MaterialPageRoute<void>(builder: (_) => ThreadScreen(threadId: (t['id'] as num).toInt())));
                      await reload();
                    },
                    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Row(children: [Expanded(child: Text('${t['companyName']}', style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700))), if ((t['unread'] as num) > 0) Pill('새 메시지 ${t['unread']}', tone: Tone.warn)]),
                      if (t['subject'] != null) Text('${t['subject']}', style: TextStyle(fontSize: 14, color: c.text2)),
                      const SizedBox(height: 4),
                      Text('${t['lastBody'] ?? ''}', maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 15)),
                      const SizedBox(height: 4),
                      Text(fmtDateTime(t['lastMessageAt'] as String), style: TextStyle(fontSize: 13, color: c.text3)),
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

class ThreadScreen extends StatefulWidget {
  const ThreadScreen({super.key, required this.threadId});
  final int threadId;
  @override
  State<ThreadScreen> createState() => _ThreadScreenState();
}

class _ThreadScreenState extends State<ThreadScreen> {
  final _text = TextEditingController();
  int _gen = 0;
  bool _busy = false;

  Future<void> _send(Session s) async {
    setState(() => _busy = true);
    try {
      await s.api.post('/me/messages/threads/${widget.threadId}/reply', {'body': _text.text.trim()});
      _text.clear();
      setState(() => _gen++);
    } catch (e) {
      if (mounted) toast(context, errorText(e), bad: true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = SessionScope.of(context);
    final c = colorsOf(context);
    return LoadingPage<Map<String, dynamic>>(
      key: ValueKey('thread$_gen'),
      title: '💬 대화',
      actions: [
        PopupMenuButton<String>(
          tooltip: '더보기',
          onSelected: (v) async {
            final t = await s.api.getMap('/me/messages/threads/${widget.threadId}');
            if (!context.mounted) return;
            if (v == 'block') {
              if (await confirmDialog(context, '이 업체를 차단할까요?', '차단하면 이 업체의 공고와 메시지가 보이지 않고 답장도 할 수 없어요.', yes: '차단할게요')) {
                await s.api.put('/me/blocks/companies/${t['companyId']}');
                if (context.mounted) setState(() => _gen++);
              }
            } else if (v == 'unblock') {
              await s.api.delete('/me/blocks/companies/${t['companyId']}');
              if (context.mounted) setState(() => _gen++);
            } else if (v == 'report') {
              await s.api.post('/reports', {'targetType': 'COMPANY', 'targetId': t['companyId'], 'reasonCode': 'INAPPROPRIATE', 'detail': '메시지 신고'});
              if (context.mounted) toast(context, '신고했어요. 확인해서 조치할게요.');
            }
          },
          itemBuilder: (_) => const [PopupMenuItem(value: 'block', child: Text('🚫 이 업체 차단')), PopupMenuItem(value: 'unblock', child: Text('차단 풀기')), PopupMenuItem(value: 'report', child: Text('🚩 신고')) ],
        ),
      ],
      load: () => s.api.getMap('/me/messages/threads/${widget.threadId}'),
      bottomBuilder: (context, t, reload) => t['blocked'] == true
          ? const AlertBox('차단한 업체예요. 답장할 수 없어요.', tone: Tone.bad)
          : Row(children: [
              Expanded(child: TextField(controller: _text, minLines: 1, maxLines: 3, onChanged: (_) => setState(() {}), decoration: InputDecoration(hintText: '답장을 써 주세요', filled: true, fillColor: c.surface, border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)), contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12)))),
              const SizedBox(width: 8),
              SizedBox(height: 52, width: 72, child: FilledButton(onPressed: _text.text.trim().isEmpty || _busy ? null : () => _send(s), child: const Text('보내기'))),
            ]),
      builder: (context, t, reload) => [
        Text('${t['companyName']}${t['subject'] != null ? ' · ${t['subject']}' : ''}', style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
        for (final m in (t['messages'] as List).cast<Map<String, dynamic>>())
          Align(
            alignment: m['senderSide'] == 'ACTOR' ? Alignment.centerRight : Alignment.centerLeft,
            child: ConstrainedBox(
              constraints: BoxConstraints(maxWidth: MediaQuery.sizeOf(context).width * .8),
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                decoration: BoxDecoration(color: m['senderSide'] == 'ACTOR' ? c.primaryWeak : c.surface, border: Border.all(color: c.line), borderRadius: BorderRadius.circular(16)),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text('${m['body']}', style: const TextStyle(fontSize: 15, height: 1.4)), const SizedBox(height: 2), Text(fmtDateTime(m['createdAt'] as String), style: TextStyle(fontSize: 12, color: c.text3))]),
              ),
            ),
          ),
      ],
    );
  }
}

class NotificationsScreen extends StatelessWidget {
  const NotificationsScreen({super.key});
  @override
  Widget build(BuildContext context) {
    final s = SessionScope.of(context);
    final c = colorsOf(context);
    return LoadingPage<List<Map<String, dynamic>>>(
      title: '🔔 알림',
      load: () async {
        final l = await s.api.getList('/notifications', query: {'limit': 50});
        await s.api.post('/notifications/read-all');
        await s.refreshUnread();
        return l;
      },
      builder: (context, list, _) => [
        if (list.isEmpty) const EmptyNote('알림이 없어요.'),
        for (final n in list)
          AppCard(
            tone: n['readAt'] == null ? c.primaryWeak : null,
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('${n['title']}', style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
              if (n['body'] != null) Text('${n['body']}', style: TextStyle(fontSize: 14, color: c.text2)),
              Text(fmtDateTime(n['createdAt'] as String), style: TextStyle(fontSize: 12, color: c.text3)),
            ]),
          ),
      ],
    );
  }
}
