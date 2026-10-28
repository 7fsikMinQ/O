import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import '../core/api.dart';
import '../core/session.dart';
import '../core/ui/widgets.dart';

/// 내 정보 메뉴: 프로필·주민번호·정산 계좌·알림 설정·차단·탈퇴.
class MyInfoScreen extends StatelessWidget {
  const MyInfoScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final s = SessionScope.of(context);
    final c = colorsOf(context);
    Widget item(String emoji, String label, Widget Function() page) => AppCard(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
          onTap: () => Navigator.of(context).push<void>(MaterialPageRoute<void>(builder: (_) => page())),
          child: SizedBox(height: 52, child: Row(children: [Text(emoji, style: const TextStyle(fontSize: 20)), const SizedBox(width: 12), Expanded(child: Text(label, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600))), Text('›', style: TextStyle(fontSize: 20, color: c.text3))])),
        );
    return AppPage(
      title: '🙂 내 정보',
      back: false,
      subtitle: '${s.me?['name'] ?? ''}님',
      children: [
        AsyncView<Map<String, dynamic>>(
          load: () => s.api.getMap('/me/activity'),
          builder: (context, a, _) => AppCard(
            child: Row(mainAxisAlignment: MainAxisAlignment.spaceAround, children: [
              _Stat('${a['confirmedCount']}', '확정'),
              _Stat('${a['attendedCount']}', '출석'),
              _Stat('${a['noshowCount']}', Terms2.noShow),
            ]),
          ),
        ),
        item('📋', '내 프로필·사진', () => const ProfileEditScreen()),
        item('🪪', '주민등록번호', () => const RrnScreen()),
        item('🏦', '정산 계좌', () => const PayoutScreen()),
        item('🔔', '알림 설정', () => const SettingsScreen()),
        item('🚫', '차단한 업체', () => const BlocksScreen()),
        item('📜', '약관·개인정보 처리방침', () => const TermsScreen()),
        AppCard(
          child: Column(children: [
            LineButton('로그아웃', onPressed: () async {
              if (await confirmDialog(context, '로그아웃', '로그아웃할까요?')) await s.logout();
            }),
            const SizedBox(height: 8),
            LineButton('탈퇴하기', bad: true, onPressed: () async {
              if (!await confirmDialog(context, '정말 탈퇴할까요?', '합격·확정된 일정이 없을 때만 탈퇴할 수 있어요. 탈퇴하면 지원 기록은 보존기간 뒤에 지워져요.', yes: '탈퇴할게요')) return;
              try {
                await s.api.delete('/me');
                await s.logout();
              } catch (e) {
                if (context.mounted) infoDialog(context, '탈퇴할 수 없어요', errorText(e));
              }
            }),
          ]),
        ),
      ],
    );
  }
}

class Terms2 {
  static const noShow = '안 나온 횟수';
}

class _Stat extends StatelessWidget {
  const _Stat(this.value, this.label);
  final String value;
  final String label;
  @override
  Widget build(BuildContext context) => Column(children: [Text(value, style: TextStyle(fontSize: 24, fontWeight: FontWeight.w800, color: colorsOf(context).primaryText)), Text(label, style: TextStyle(fontSize: 13, color: colorsOf(context).text3))]);
}

/// 프로필: 지원서에 복사돼 업체가 보는 정보. 주민번호가 등록되면 생년월일·성별은 바꿀 수 없다.
class ProfileEditScreen extends StatefulWidget {
  const ProfileEditScreen({super.key});
  @override
  State<ProfileEditScreen> createState() => _ProfileEditScreenState();
}

class _ProfileEditScreenState extends State<ProfileEditScreen> {
  final _birth = TextEditingController();
  final _height = TextEditingController();
  final _weight = TextEditingController();
  final _top = TextEditingController();
  final _bottom = TextEditingController();
  final _shoe = TextEditingController();
  final _address = TextEditingController();
  final _address2 = TextEditingController();
  final _departure = TextEditingController();
  final _intro = TextEditingController();
  final _phone = TextEditingController();
  String _gender = 'MALE';
  String? _hair;
  bool _tattoo = false;
  bool _loaded = false;
  bool _exists = false;
  bool _hasPhone = false;
  bool _locked = false;
  bool _busy = false;
  String? _err;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final s = SessionScope.read(context);
    try {
      final p = await s.api.getMap('/me/actor-profile');
      _exists = true;
      _birth.text = '${p['birthDate'] ?? ''}';
      _gender = '${p['gender'] ?? 'MALE'}';
      _height.text = '${p['heightCm'] ?? ''}';
      _weight.text = '${p['weightKg'] ?? ''}';
      _top.text = '${p['topSize'] ?? ''}';
      _bottom.text = '${p['bottomSize'] ?? ''}';
      _shoe.text = '${p['shoeSize'] ?? ''}';
      _hair = p['hairLength'] as String?;
      _tattoo = p['hasTattoo'] == true;
      _address.text = '${p['homeAddress'] ?? ''}';
      _address2.text = '${p['homeAddressDetail'] ?? ''}';
      _departure.text = '${p['defaultDepartureLocation'] ?? ''}';
      _intro.text = '${p['introduction'] ?? ''}';
      _hasPhone = p['hasPhone'] == true;
      _locked = p['hasTaxIdentity'] == true;
    } on ApiException catch (e) {
      if (e.status != 404) _err = e.message;
    }
    if (mounted) setState(() => _loaded = true);
  }

  Future<void> _save() async {
    final s = SessionScope.of(context);
    setState(() {
      _busy = true;
      _err = null;
    });
    int? n(TextEditingController c) => int.tryParse(c.text.trim());
    try {
      await s.api.put('/me/actor-profile', {
        'birthDate': _birth.text.trim(),
        'gender': _gender,
        'heightCm': n(_height),
        'weightKg': n(_weight),
        'topSize': _top.text.trim().isEmpty ? null : _top.text.trim(),
        'bottomSize': _bottom.text.trim().isEmpty ? null : _bottom.text.trim(),
        'shoeSize': n(_shoe),
        'hairLength': _hair,
        'hasTattoo': _tattoo,
        'homeAddress': _address.text.trim().isEmpty ? null : _address.text.trim(),
        'homeAddressDetail': _address2.text.trim().isEmpty ? null : _address2.text.trim(),
        'defaultDepartureLocation': _departure.text.trim().isEmpty ? null : _departure.text.trim(),
        'introduction': _intro.text.trim().isEmpty ? null : _intro.text.trim(),
        if (_phone.text.trim().isNotEmpty) 'phone': _phone.text.trim(),
      });
      if (mounted) {
        toast(context, '저장했어요');
        Navigator.of(context).pop();
      }
    } catch (e) {
      setState(() => _err = errorText(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = colorsOf(context);
    final byEmail = SessionScope.of(context).otpChannel == 'email';
    if (!_loaded) return const AppPage(title: '📋 내 프로필', children: [Padding(padding: EdgeInsets.all(48), child: Center(child: CircularProgressIndicator()))]);
    return AppPage(
      title: '📋 내 프로필',
      subtitle: '업체가 지원서에서 보는 정보예요. ${_exists ? '' : '처음이니 모두 적어 주세요.'}',
      bottom: PrimaryButton('저장하기', onPressed: _save, busy: _busy),
      children: [
        if (_locked) const AlertBox('주민번호를 등록해서 생년월일·성별은 바꿀 수 없어요. 바꾸려면 고객센터로 문의해 주세요.'),
        AppCard(
          child: Column(children: [
            Row(children: [
              Expanded(child: LabeledField(label: '생년월일', controller: _birth, hint: '1990-01-01', keyboard: TextInputType.datetime, formatters: [FilteringTextInputFormatter.allow(RegExp(r'[0-9-]'))], maxLength: 10)),
              const SizedBox(width: 12),
              Expanded(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text('성별', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: c.text2)),
                  const SizedBox(height: 6),
                  SegmentedButton<String>(
                    segments: const [ButtonSegment(value: 'MALE', label: Text('남')), ButtonSegment(value: 'FEMALE', label: Text('여'))],
                    selected: {_gender == 'OTHER' ? 'MALE' : _gender},
                    onSelectionChanged: _locked ? null : (v) => setState(() => _gender = v.first),
                    showSelectedIcon: false,
                  ),
                ]),
              ),
            ]),
            const SizedBox(height: 12),
            Row(children: [
              Expanded(child: LabeledField(label: '키 (cm)', controller: _height, keyboard: TextInputType.number, maxLength: 3)),
              const SizedBox(width: 12),
              Expanded(child: LabeledField(label: '몸무게 (kg)', controller: _weight, keyboard: TextInputType.number, maxLength: 3)),
            ]),
            const SizedBox(height: 12),
            Row(children: [
              Expanded(child: LabeledField(label: '상의', controller: _top, maxLength: 10)),
              const SizedBox(width: 8),
              Expanded(child: LabeledField(label: '하의', controller: _bottom, maxLength: 10)),
              const SizedBox(width: 8),
              Expanded(child: LabeledField(label: '신발(mm)', controller: _shoe, keyboard: TextInputType.number, maxLength: 3)),
            ]),
            const SizedBox(height: 12),
            DropdownButtonFormField<String?>(
              initialValue: _hair,
              decoration: const InputDecoration(labelText: '머리 길이'),
              items: const [DropdownMenuItem(value: null, child: Text('선택 안 함')), DropdownMenuItem(value: 'SHAVED', child: Text('삭발')), DropdownMenuItem(value: 'SHORT', child: Text('짧게')), DropdownMenuItem(value: 'MEDIUM', child: Text('중간')), DropdownMenuItem(value: 'LONG', child: Text('길게'))],
              onChanged: (v) => setState(() => _hair = v),
            ),
            SwitchListTile(contentPadding: EdgeInsets.zero, title: const Text('문신이 있어요'), value: _tattoo, onChanged: (v) => setState(() => _tattoo = v)),
          ]),
        ),
        AppCard(
          child: Column(children: [
            if (byEmail && !_hasPhone) ...[
              LabeledField(label: '휴대폰 번호 (업체가 연락할 번호)', controller: _phone, hint: '010-1234-5678', keyboard: TextInputType.phone, maxLength: 13),
              const SizedBox(height: 4),
              Align(alignment: Alignment.centerLeft, child: Text('지원하면 업체가 이 번호를 볼 수 있어요.', style: TextStyle(fontSize: 13, color: c.text3))),
              const SizedBox(height: 12),
            ],
            LabeledField(label: '집 주소 (합격 통보 뒤에만 업체에 보여요)', controller: _address, maxLength: 200),
            const SizedBox(height: 12),
            LabeledField(label: '상세 주소', controller: _address2, maxLength: 200),
            const SizedBox(height: 12),
            LabeledField(label: '기본 탑승 희망지', controller: _departure, maxLength: 200),
            const SizedBox(height: 12),
            LabeledField(label: '소개 (선택)', controller: _intro, maxLines: 3, maxLength: 1000),
          ]),
        ),
        if (_err != null) AlertBox(_err!, tone: Tone.bad),
      ],
    );
  }
}

/// 주민등록번호 등록. 가입 때가 아니라 첫 지원 전에만 받는다. 화면에는 항상 가려서 보인다.
class RrnScreen extends StatefulWidget {
  const RrnScreen({super.key});
  @override
  State<RrnScreen> createState() => _RrnScreenState();
}

class _RrnScreenState extends State<RrnScreen> {
  final _rrn = TextEditingController();
  bool _agree = false;
  bool _busy = false;
  String? _err;

  @override
  Widget build(BuildContext context) {
    final s = SessionScope.of(context);
    return LoadingPage<Map<String, dynamic>>(
      title: '🪪 주민등록번호',
      subtitle: '급여 신고 때 필요해서 업체(원천징수 의무자)가 써요. 암호화해서 보관하고 화면에는 가려서 보여요.',
      load: () async {
        final status = await s.api.getMap('/me/actor-profile/tax-identity');
        final terms = await s.api.getList('/terms');
        final t = terms.firstWhere((x) => x['type'] == 'SENSITIVE_ID');
        return {'status': status, 'term': t};
      },
      bottomBuilder: (context, d, reload) => PrimaryButton('등록하기', busy: _busy, onPressed: (_agree && _rrn.text.replaceAll('-', '').length == 13) ? () => _register(d, reload) : null),
      builder: (context, d, reload) {
        final st = d['status'] as Map<String, dynamic>;
        final term = d['term'] as Map<String, dynamic>;
        return [
          if (st['registered'] == true) AlertBox('등록되어 있어요: ${st['masked']}  다른 번호로 바꾸려면 아래에 새로 입력해 주세요.', tone: Tone.ok),
          AppCard(child: LabeledField(label: '주민등록번호', controller: _rrn, hint: '900101-1234567', keyboard: TextInputType.number, maxLength: 14, formatters: [FilteringTextInputFormatter.allow(RegExp(r'[0-9-]'))], onChanged: (_) => setState(() {}))),
          AppCard(
            child: CheckboxListTile(
              contentPadding: EdgeInsets.zero,
              controlAffinity: ListTileControlAffinity.leading,
              value: _agree,
              onChanged: (v) => setState(() => _agree = v ?? false),
              title: const Text('(필수) 고유식별정보(주민등록번호) 처리에 동의해요', style: TextStyle(fontSize: 15)),
              subtitle: TextButton(onPressed: () => infoDialog(context, '고유식별정보 처리 동의', '${term['content']}'), child: const Align(alignment: Alignment.centerLeft, child: Text('내용 보기'))),
            ),
          ),
          if (_err != null) AlertBox(_err!, tone: Tone.bad),
        ];
      },
    );
  }

  Future<void> _register(Map<String, dynamic> d, Future<void> Function() reload) async {
    final s = SessionScope.of(context);
    setState(() {
      _busy = true;
      _err = null;
    });
    try {
      final digits = _rrn.text.replaceAll('-', '');
      await s.api.put('/me/actor-profile/tax-identity', {'rrn': '${digits.substring(0, 6)}-${digits.substring(6)}', 'termVersion': (d['term'] as Map)['version']});
      if (mounted) {
        toast(context, '등록했어요');
        Navigator.of(context).pop();
      }
    } catch (e) {
      setState(() => _err = errorText(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }
}

/// 정산 계좌: 지원할 때 "내 계좌 불러오기"로 쓰인다. 예금주가 내 이름과 다르면 알려 준다. 플랫폼은 돈을 보내지 않는다.
class PayoutScreen extends StatefulWidget {
  const PayoutScreen({super.key});
  @override
  State<PayoutScreen> createState() => _PayoutScreenState();
}

class _PayoutScreenState extends State<PayoutScreen> {
  final _number = TextEditingController();
  final _holder = TextEditingController();
  String _bank = '004';
  bool _busy = false;
  String? _err;

  @override
  Widget build(BuildContext context) {
    final s = SessionScope.of(context);
    return LoadingPage<Map<String, dynamic>>(
      title: '🏦 정산 계좌',
      subtitle: '급여를 받을 계좌예요. 본인 이름의 계좌만 쓸 수 있어요.',
      load: () async {
        Map<String, dynamic>? cur;
        try {
          cur = await s.api.getMap('/me/actor-profile/payout');
        } on ApiException catch (e) {
          if (e.status != 404) rethrow;
        }
        return {'banks': await s.api.getList('/master/banks'), 'current': cur};
      },
      bottomBuilder: (context, d, reload) => PrimaryButton('저장하기', busy: _busy, onPressed: (_number.text.trim().length >= 8 && _holder.text.trim().isNotEmpty) ? () => _save(reload) : null),
      builder: (context, d, reload) {
        final banks = (d['banks'] as List).cast<Map<String, dynamic>>();
        final cur = d['current'] as Map<String, dynamic>?;
        if (_holder.text.isEmpty) _holder.text = (cur?['holder'] as String?) ?? '${s.me?['name'] ?? ''}';
        return [
          if (cur != null) AlertBox('저장된 계좌: ${cur['bankName']} ${cur['accountMasked']} · ${cur['holder']}${cur['holderMatchesName'] == true ? ' ✅ 내 이름과 같아요' : ''}', tone: Tone.ok),
          AppCard(
            child: Column(children: [
              DropdownButtonFormField<String>(initialValue: _bank, decoration: const InputDecoration(labelText: '은행'), items: [for (final b in banks) DropdownMenuItem(value: b['code'] as String, child: Text(b['name'] as String))], onChanged: (v) => setState(() => _bank = v ?? _bank)),
              const SizedBox(height: 12),
              LabeledField(label: '계좌번호 (숫자만)', controller: _number, keyboard: TextInputType.number, maxLength: 20, onChanged: (_) => setState(() {})),
              const SizedBox(height: 12),
              LabeledField(label: '예금주', controller: _holder, onChanged: (_) => setState(() {})),
            ]),
          ),
          if (_err != null) AlertBox(_err!, tone: Tone.bad),
        ];
      },
    );
  }

  Future<void> _save(Future<void> Function() reload) async {
    final s = SessionScope.of(context);
    setState(() {
      _busy = true;
      _err = null;
    });
    try {
      await s.api.put('/me/actor-profile/payout', {'bankCode': _bank, 'accountNumber': _number.text.trim(), 'holder': _holder.text.trim()});
      _number.clear();
      if (mounted) {
        toast(context, '저장했어요');
        Navigator.of(context).pop();
      }
    } catch (e) {
      setState(() => _err = errorText(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }
}

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});
  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  bool _star = true;

  @override
  Widget build(BuildContext context) {
    final s = SessionScope.of(context);
    return AppPage(
      title: '🔔 알림 설정',
      children: [
        AppCard(
          child: SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('⭐ 업체의 새 공고 먼저 받기', style: TextStyle(fontWeight: FontWeight.w600)),
            subtitle: const Text('함께한 업체가 나를 ⭐ 로 저장하면 새 공고를 1분 먼저 알려 드려요. 끄면 일반 알림만 받아요.'),
            value: _star,
            onChanged: (v) async {
              setState(() => _star = v);
              try {
                await s.api.put('/me/settings/star-alerts', {'enabled': v});
              } catch (e) {
                if (context.mounted) toast(context, errorText(e), bad: true);
              }
            },
          ),
        ),
        const AlertBox('iPhone 은 홈 화면에 추가해야 알림을 받을 수 있어요. 공유 버튼 ⬆️ → "홈 화면에 추가"를 눌러 주세요.', tone: Tone.warn),
      ],
    );
  }
}

class BlocksScreen extends StatelessWidget {
  const BlocksScreen({super.key});
  @override
  Widget build(BuildContext context) => const AppPage(title: '🚫 차단한 업체', children: [EmptyNote('업체를 차단하려면 메시지 화면의 ⋯ 메뉴에서 "이 업체 차단"을 눌러 주세요.\n차단하면 그 업체의 공고와 메시지가 보이지 않아요.')]);
}

class TermsScreen extends StatelessWidget {
  const TermsScreen({super.key});
  @override
  Widget build(BuildContext context) {
    final s = SessionScope.of(context);
    return LoadingPage<List<Map<String, dynamic>>>(
      title: '📜 약관',
      load: () => s.api.getList('/terms'),
      builder: (context, terms, _) => [
        for (final t in terms)
          AppCard(
            onTap: () => infoDialog(context, '${t['type']} v${t['version']}', '${t['content']}'),
            child: Row(children: [Expanded(child: Text('${_name(t['type'] as String)} · v${t['version']}', style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600))), const Text('›')]),
          ),
      ],
    );
  }

  static String _name(String t) => switch (t) {
        'SERVICE' => '서비스 이용약관',
        'PRIVACY' => '개인정보 수집·이용',
        'THIRD_PARTY_PROVISION' => '업체에 번호 제공',
        'SENSITIVE_ID' => '고유식별정보 처리',
        'E_SIGNATURE' => '전자서명·증빙 보관',
        _ => '마케팅 수신',
      };
}
