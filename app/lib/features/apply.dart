import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:image_picker/image_picker.dart';
import 'package:material_ui/material_ui.dart';

import '../core/api.dart';
import '../core/format.dart';
import '../core/session.dart';
import '../core/ui/widgets.dart';
import 'myinfo.dart';

/// 손가락으로 서명하는 판. [SignatureController] 로 지우기·PNG 내보내기.
class SignatureController extends ChangeNotifier {
  final List<List<Offset>> strokes = [];
  bool get isEmpty => strokes.isEmpty;
  void clear() {
    strokes.clear();
    notifyListeners();
  }

  void _changed() => notifyListeners();

  /// 흰 배경에 검은 선으로 그려 PNG(바이트)로.
  Future<Uint8List> toPng(Size size) async {
    const scale = 2.0;
    final rec = ui.PictureRecorder();
    final canvas = Canvas(rec);
    canvas.scale(scale);
    canvas.drawRect(Offset.zero & size, Paint()..color = const Color(0xFFFFFFFF));
    _paint(canvas, const Color(0xFF111111));
    final img = await rec.endRecording().toImage((size.width * scale).round(), (size.height * scale).round());
    final data = await img.toByteData(format: ui.ImageByteFormat.png);
    return data!.buffer.asUint8List();
  }

  void _paint(Canvas canvas, Color color) {
    final p = Paint()
      ..color = color
      ..strokeWidth = 3
      ..strokeCap = StrokeCap.round
      ..style = PaintingStyle.stroke;
    for (final s in strokes) {
      if (s.length == 1) {
        canvas.drawCircle(s.first, 1.5, p..style = PaintingStyle.fill);
        p.style = PaintingStyle.stroke;
      } else {
        final path = Path()..moveTo(s.first.dx, s.first.dy);
        for (final o in s.skip(1)) {
          path.lineTo(o.dx, o.dy);
        }
        canvas.drawPath(path, p);
      }
    }
  }
}

class SignaturePad extends StatelessWidget {
  const SignaturePad({super.key, required this.controller, this.height = 160});
  final SignatureController controller;
  final double height;

  @override
  Widget build(BuildContext context) {
    final c = colorsOf(context);
    return LayoutBuilder(builder: (context, box) {
      final size = Size(box.maxWidth, height);
      return Semantics(
        label: '서명 칸. 손가락으로 이름을 써 주세요.',
        child: Container(
          height: height,
          decoration: BoxDecoration(color: const Color(0xFFFFFFFF), borderRadius: BorderRadius.circular(16), border: Border.all(color: c.primary, width: 1.5)),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(16),
            child: GestureDetector(
              behavior: HitTestBehavior.opaque,
              onPanStart: (d) {
                controller.strokes.add([d.localPosition]);
                controller._changed();
              },
              onPanUpdate: (d) {
                controller.strokes.last.add(d.localPosition);
                controller._changed();
              },
              child: ListenableBuilder(listenable: controller, builder: (_, _) => CustomPaint(size: size, painter: _SigPainter(controller), child: controller.isEmpty ? const Center(child: Text('손가락으로 이름을 써 주세요', style: TextStyle(color: Color(0xFF6B6B6B), fontSize: 14))) : null)),
            ),
          ),
        ),
      );
    });
  }
}

class _SigPainter extends CustomPainter {
  _SigPainter(this.c) : super(repaint: c);
  final SignatureController c;
  @override
  void paint(Canvas canvas, Size size) => c._paint(canvas, const Color(0xFF111111));
  @override
  bool shouldRepaint(covariant _SigPainter old) => true;
}

class ApplyFlow extends StatefulWidget {
  const ApplyFlow({super.key, required this.positionId, required this.roleTitle});
  final int positionId;
  final String roleTitle;
  @override
  State<ApplyFlow> createState() => _ApplyFlowState();
}

class _ApplyFlowState extends State<ApplyFlow> {
  int _step = 0;
  Map<String, dynamic>? _form;
  String? _loadErr;
  Uint8List? _photo;
  final _sig = SignatureController();
  final _message = TextEditingController();
  final _departure = TextEditingController();
  final _bankNumber = TextEditingController();
  final _holder = TextEditingController();
  String _bank = '004';
  bool _otherAccount = false;
  bool _agree = false;
  bool _busy = false;
  String? _err;
  List<Map<String, dynamic>> _banks = const [];

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final s = SessionScope.read(context);
    try {
      final f = await s.api.getMap('/positions/${widget.positionId}/apply-form');
      _banks = await s.api.getList('/master/banks');
      if (!mounted) return;
      setState(() {
        _form = f;
        _departure.text = '${(f['profile'] as Map?)?['defaultDepartureLocation'] ?? ''}';
        _otherAccount = f['hasPayoutAccount'] != true;
        _holder.text = (s.me?['name'] as String?) ?? '';
      });
    } catch (e) {
      if (mounted) setState(() => _loadErr = errorText(e));
    }
  }

  Future<void> _pick(ImageSource src) async {
    final x = await ImagePicker().pickImage(source: src, maxWidth: 1600, imageQuality: 85);
    if (x == null) return;
    final bytes = await x.readAsBytes();
    if (!mounted) return;
    if (bytes.length > 5 * 1024 * 1024) {
      await infoDialog(context, '사진이 너무 커요', '사진은 5MB 이하만 올릴 수 있어요. 더 작은 사진을 골라 주세요. (현재 ${(bytes.length / 1024 / 1024).toStringAsFixed(1)}MB)');
      return;
    }
    setState(() => _photo = bytes);
  }

  Future<void> _submit() async {
    final s = SessionScope.of(context);
    setState(() {
      _busy = true;
      _err = null;
    });
    try {
      final photo = await s.api.upload('/me/apply-files', _photo!, 'photo.jpg', {'kind': 'PHOTO'});
      final sigBytes = await _sig.toPng(const Size(300, 120));
      final sig = await s.api.upload('/me/apply-files', sigBytes, 'signature.png', {'kind': 'SIGNATURE'});
      await s.api.post('/positions/${widget.positionId}/applications', {
        'photoFileId': photo['fileId'],
        'signatureFileId': sig['fileId'],
        'agreeESignature': _agree,
        'message': _message.text.trim().isEmpty ? null : _message.text.trim(),
        'departureLocation': _departure.text.trim().isEmpty ? null : _departure.text.trim(),
        'useProfileAccount': !_otherAccount,
        if (_otherAccount) 'account': {'bankCode': _bank, 'accountNumber': _bankNumber.text.trim(), 'holder': _holder.text.trim()},
      });
      if (mounted) setState(() => _step = 3);
    } catch (e) {
      setState(() => _err = errorText(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Widget _steps() {
    final c = colorsOf(context);
    return Row(children: [for (var i = 0; i < 3; i++) Expanded(child: Container(height: 6, margin: const EdgeInsets.symmetric(horizontal: 3), decoration: BoxDecoration(color: i <= _step ? c.primary : c.line, borderRadius: BorderRadius.circular(3))))]);
  }

  @override
  Widget build(BuildContext context) {
    final c = colorsOf(context);
    if (_loadErr != null) return AppPage(title: '✍️ 지원하기', children: [AlertBox(_loadErr!, tone: Tone.bad)]);
    if (_form == null) return const AppPage(title: '✍️ 지원하기', children: [Padding(padding: EdgeInsets.all(48), child: Center(child: CircularProgressIndicator()))]);
    final f = _form!;
    final missing = (f['missing'] as List).cast<String>();
    final reasons = (f['reasons'] as List).cast<String>();
    final term = f['eSignatureTerm'] as Map<String, dynamic>?;
    final conflicts = (f['conflicts'] as List).cast<Map<String, dynamic>>();

    if (_step == 3) {
      return AppPage(
        title: '🎉 지원했어요',
        back: false,
        subtitle: '업체가 보고 뽑으면 알려 드려요.',
        bottom: Column(mainAxisSize: MainAxisSize.min, children: [PrimaryButton('내 일정 보기', onPressed: () => Navigator.of(context).pop(true)), const SizedBox(height: 8), LineButton('다른 일 더 찾기', onPressed: () => Navigator.of(context).popUntil((r) => r.isFirst))]),
        children: [
          AppCard(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(widget.roleTitle, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700)), const SizedBox(height: 4), Text('내 지원서는 증빙으로 5년간 보관돼요.', style: TextStyle(fontSize: 14, color: c.text2))])),
          const AlertBox('지원은 뽑히기 전에 언제든 취소할 수 있어요.', tone: Tone.ok),
        ],
      );
    }

    final titles = ['1단계 · 내 정보 확인', '2단계 · 현재 사진', '3단계 · 서명'];
    final canNext0 = f['eligible'] == true && (_otherAccount ? (_bankNumber.text.trim().length >= 8 && _holder.text.trim().isNotEmpty) : true);
    return AppPage(
      title: '✍️ 지원하기',
      subtitle: '${titles[_step]} (${_step + 1}/3) · ${widget.roleTitle}',
      bottom: Column(mainAxisSize: MainAxisSize.min, children: [
        if (_step == 0) PrimaryButton('다음: 내 사진 찍기 📷', onPressed: canNext0 ? () => setState(() => _step = 1) : null),
        if (_step == 1) PrimaryButton('다음: 서명하기 ✍️', onPressed: _photo == null ? null : () => setState(() => _step = 2)),
        if (_step == 2) PrimaryButton('지원 완료 ✅', busy: _busy, onPressed: (_sig.isEmpty || (term != null && !_agree)) ? null : _submit),
        if (_step > 0) TextLink('이전으로', onPressed: () => setState(() => _step -= 1)),
      ]),
      children: [
        _steps(),
        if (_step == 0) ...[
          if (reasons.isNotEmpty) AlertBox(reasons.join('\n'), tone: Tone.bad),
          if (missing.isNotEmpty)
            AppCard(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                const Text('먼저 채워 주세요', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
                const SizedBox(height: 8),
                if (missing.contains('PROFILE')) LineButton('내 프로필 쓰기', onPressed: () async { await Navigator.of(context).push<void>(MaterialPageRoute<void>(builder: (_) => const ProfileEditScreen())); _load(); }),
                if (missing.contains('PHONE')) ...[const SizedBox(height: 8), LineButton('연락처(휴대폰 번호) 쓰기', onPressed: () async { await Navigator.of(context).push<void>(MaterialPageRoute<void>(builder: (_) => const ProfileEditScreen())); _load(); })],
                if (missing.contains('RRN')) ...[const SizedBox(height: 8), LineButton('주민등록번호 등록하기', onPressed: () async { await Navigator.of(context).push<void>(MaterialPageRoute<void>(builder: (_) => const RrnScreen())); _load(); })],
              ]),
            ),
          if (f['profile'] != null)
            AppCard(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                const Text('내 정보', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
                const SizedBox(height: 4),
                KeyValue('이름', '${SessionScope.of(context).me?['name']}'),
                KeyValue('키·몸무게', '${(f['profile'] as Map)['heightCm']}cm · ${(f['profile'] as Map)['weightKg']}kg'),
                KeyValue('생년월일', '${(f['profile'] as Map)['birthDate']}'),
              ]),
            ),
          if (conflicts.isNotEmpty) AlertBox('이 일정과 시간이 가까운 다른 일이 있어요: ${conflicts.map((x) => '${x['postingTitle']} (${fmtRange(x['workStartAt'] as String, x['workEndAt'] as String)})').join(', ')}'),
          AppCard(child: LabeledField(label: '타고 갈 곳 (탑승 희망지)', controller: _departure, maxLength: 200)),
          AppCard(child: LabeledField(label: '업체에 한마디 (선택)', controller: _message, maxLines: 2, maxLength: 1000)),
          AppCard(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Text('정산 계좌', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
              const SizedBox(height: 4),
              if (f['hasPayoutAccount'] == true)
                SwitchListTile(contentPadding: EdgeInsets.zero, title: const Text('다른 계좌를 직접 입력할래요'), value: _otherAccount, onChanged: (v) => setState(() => _otherAccount = v))
              else
                Text('저장된 계좌가 없어서 직접 입력해 주세요.', style: TextStyle(fontSize: 14, color: c.text2)),
              if (_otherAccount) ...[
                const SizedBox(height: 8),
                DropdownButtonFormField<String>(
                  initialValue: _bank,
                  decoration: const InputDecoration(labelText: '은행'),
                  items: [for (final b in _banks) DropdownMenuItem(value: b['code'] as String, child: Text(b['name'] as String))],
                  onChanged: (v) => setState(() => _bank = v ?? _bank),
                ),
                const SizedBox(height: 8),
                LabeledField(label: '계좌번호 (숫자만)', controller: _bankNumber, keyboard: TextInputType.number, onChanged: (_) => setState(() {})),
                const SizedBox(height: 8),
                LabeledField(label: '예금주', controller: _holder, onChanged: (_) => setState(() {})),
              ],
            ]),
          ),
        ],
        if (_step == 1) ...[
          const AlertBox('지금 모습이 잘 보이는 사진을 올려 주세요. 얼굴이 밝게 나오는 곳이 좋아요. 사진은 업체에만 보여요.', tone: Tone.warn),
          AppCard(
            child: Column(children: [
              Container(
                height: 280,
                width: double.infinity,
                decoration: BoxDecoration(color: c.primaryWeak, borderRadius: BorderRadius.circular(16)),
                clipBehavior: Clip.antiAlias,
                child: _photo == null ? const Center(child: Text('🙂', style: TextStyle(fontSize: 72))) : Image.memory(_photo!, fit: BoxFit.cover),
              ),
              const SizedBox(height: 12),
              PrimaryButton(_photo == null ? '사진 찍기 📷' : '다시 찍기 📷', onPressed: () => _pick(ImageSource.camera)),
              const SizedBox(height: 8),
              LineButton('앨범에서 고르기', onPressed: () => _pick(ImageSource.gallery)),
              const SizedBox(height: 8),
              Text('사진은 5MB 이하만 올릴 수 있어요.', style: TextStyle(fontSize: 13, color: c.text3)),
            ]),
          ),
        ],
        if (_step == 2) ...[
          const AlertBox('이 지원서가 내가 쓴 것이라는 뜻으로 서명해 주세요. 지원서와 서명은 증빙으로 보관돼요. 근로계약서는 현장에서 따로 써요.', tone: Tone.warn),
          SignaturePad(controller: _sig),
          Align(alignment: Alignment.centerRight, child: TextLink('다시 쓰기', onPressed: _sig.clear)),
          if (term != null)
            AppCard(
              child: CheckboxListTile(
                contentPadding: EdgeInsets.zero,
                controlAffinity: ListTileControlAffinity.leading,
                value: _agree,
                onChanged: (v) => setState(() => _agree = v ?? false),
                title: const Text('(필수) 전자서명 및 증빙 보관에 동의해요', style: TextStyle(fontSize: 15)),
                subtitle: TextButton(onPressed: () => infoDialog(context, '전자서명 및 증빙 보관', '${term['content']}'), child: const Align(alignment: Alignment.centerLeft, child: Text('내용 보기'))),
              ),
            ),
          if (_err != null) AlertBox(_err!, tone: Tone.bad),
        ],
        if (_step < 2 && _err != null) AlertBox(_err!, tone: Tone.bad),
      ],
    );
  }
}
