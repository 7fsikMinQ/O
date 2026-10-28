import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import '../core/api.dart';
import '../core/session.dart';
import '../core/ui/widgets.dart';

/// 시작하기: 설정된 인증 채널에 따라 이메일 또는 휴대폰 번호를 받는다(문자 비용이 없는 이메일이 기본).
class EntryScreen extends StatefulWidget {
  const EntryScreen({super.key});
  @override
  State<EntryScreen> createState() => _EntryScreenState();
}

class _EntryScreenState extends State<EntryScreen> {
  final _id = TextEditingController();
  bool _busy = false;
  String? _err;

  Future<void> _send() async {
    final s = SessionScope.of(context);
    final byEmail = s.otpChannel == 'email';
    setState(() {
      _busy = true;
      _err = null;
    });
    try {
      await s.api.post('/auth/otp/send', byEmail ? {'email': _id.text.trim()} : {'phone': _id.text.trim()});
      if (!mounted) return;
      await Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => CodeScreen(identifier: _id.text.trim())));
    } catch (e) {
      setState(() => _err = errorText(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = SessionScope.of(context);
    final byEmail = s.otpChannel == 'email';
    final c = colorsOf(context);
    return AppPage(
      title: '👋 시작하기',
      back: false,
      subtitle: byEmail ? '이메일로 인증번호를 보내 드려요. 비밀번호는 없어요.' : '휴대폰 번호로 인증번호를 보내 드려요. 비밀번호는 없어요.',
      bottom: Column(mainAxisSize: MainAxisSize.min, children: [
        PrimaryButton(byEmail ? '인증번호 받기 📧' : '인증번호 받기 💬', onPressed: _id.text.trim().isEmpty ? null : _send, busy: _busy),
        TextLink('개인정보 처리방침 보기', onPressed: () => infoDialog(context, '개인정보 처리방침', '처리방침 전문은 앱 안 "내 정보 > 약관·개인정보 처리방침" 과 웹 페이지에서도 볼 수 있어요.')),
      ]),
      children: [
        AppCard(child: LabeledField(label: byEmail ? '이메일' : '휴대폰 번호', controller: _id, keyboard: byEmail ? TextInputType.emailAddress : TextInputType.phone, hint: byEmail ? 'name@example.com' : '010-1234-5678', onChanged: (_) => setState(() {}))),
        if (_err != null) AlertBox(_err!, tone: Tone.bad),
        Text(byEmail ? '메일이 안 오면 스팸함도 확인해 주세요.' : '문자가 안 오면 번호를 다시 확인해 주세요.', style: TextStyle(fontSize: 13, color: c.text3)),
      ],
    );
  }
}

/// 인증번호 6자리 입력.
class CodeScreen extends StatefulWidget {
  const CodeScreen({super.key, required this.identifier});
  final String identifier;
  @override
  State<CodeScreen> createState() => _CodeScreenState();
}

class _CodeScreenState extends State<CodeScreen> {
  final _code = TextEditingController();
  bool _busy = false;
  String? _err;

  Future<void> _verify() async {
    final s = SessionScope.of(context);
    final byEmail = s.otpChannel == 'email';
    setState(() {
      _busy = true;
      _err = null;
    });
    try {
      final r = await s.api.post('/auth/otp/verify', {if (byEmail) 'email': widget.identifier else 'phone': widget.identifier, 'code': _code.text.trim()});
      if (!mounted) return;
      if (r['status'] == 'LOGGED_IN') {
        await s.loginWith(r);
        if (mounted) Navigator.of(context).popUntil((r) => r.isFirst);
      } else {
        await Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => SignupScreen(signupToken: r['signupToken'] as String)));
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
    return AppPage(
      title: '🔢 숫자 6자리 입력',
      subtitle: '${widget.identifier} 로 보낸 번호를 3분 안에 넣어 주세요.',
      bottom: PrimaryButton('확인', onPressed: _code.text.trim().length == 6 ? _verify : null, busy: _busy),
      children: [
        AppCard(
          child: LabeledField(
            label: '인증번호',
            controller: _code,
            keyboard: TextInputType.number,
            maxLength: 6,
            formatters: [FilteringTextInputFormatter.digitsOnly],
            hint: '123456',
            onChanged: (_) => setState(() {}),
          ),
        ),
        if (_err != null) AlertBox(_err!, tone: Tone.bad),
        Text('번호를 5번 틀리면 잠시 막혀요.', style: TextStyle(fontSize: 13, color: c.text3)),
      ],
    );
  }
}

/// 이름과 약관 동의로 가입 마무리. 필수 약관 3가지에 동의해야 한다(주민번호는 가입이 아니라 첫 지원 전에 받는다).
class SignupScreen extends StatefulWidget {
  const SignupScreen({super.key, required this.signupToken});
  final String signupToken;
  @override
  State<SignupScreen> createState() => _SignupScreenState();
}

class _SignupScreenState extends State<SignupScreen> {
  final _name = TextEditingController();
  final Map<int, bool> _agreed = {};
  bool _busy = false;
  String? _err;
  List<Map<String, dynamic>> _terms = const [];

  static const _signupTypes = {'SERVICE', 'PRIVACY', 'THIRD_PARTY_PROVISION', 'MARKETING'};

  @override
  void initState() {
    super.initState();
    SessionScope.read(context).api.getList('/terms').then((t) {
      if (mounted) setState(() => _terms = t.where((x) => _signupTypes.contains(x['type'])).toList());
    });
  }

  static String _label(Map<String, dynamic> t) => switch (t['type']) {
        'SERVICE' => '서비스 이용약관',
        'PRIVACY' => '개인정보 수집·이용',
        'THIRD_PARTY_PROVISION' => '지원한 업체에 번호 제공',
        _ => '소식 받기',
      };

  bool get _ready => _name.text.trim().isNotEmpty && _terms.where((t) => t['required'] == true).every((t) => _agreed[t['id'] as int] == true);

  Future<void> _signup() async {
    final s = SessionScope.of(context);
    setState(() {
      _busy = true;
      _err = null;
    });
    try {
      final ids = _agreed.entries.where((e) => e.value).map((e) => e.key).toList();
      final r = await s.api.post('/auth/signup', {'signupToken': widget.signupToken, 'name': _name.text.trim(), 'agreedTermIds': ids});
      await s.loginWith(r);
      if (mounted) Navigator.of(context).popUntil((r) => r.isFirst);
    } catch (e) {
      setState(() => _err = errorText(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = colorsOf(context);
    final allRequired = _terms.where((t) => t['required'] == true).every((t) => _agreed[t['id'] as int] == true);
    return AppPage(
      title: '📝 가입하기',
      subtitle: '이름을 적고 동의해 주세요.',
      bottom: PrimaryButton('가입하기 ✅', onPressed: _ready ? _signup : null, busy: _busy),
      children: [
        AppCard(child: LabeledField(label: '이름', controller: _name, maxLength: 50, onChanged: (_) => setState(() {}))),
        AppCard(
          child: Column(children: [
            CheckboxListTile(
              contentPadding: EdgeInsets.zero,
              controlAffinity: ListTileControlAffinity.leading,
              value: allRequired && _terms.isNotEmpty,
              onChanged: (v) => setState(() {
                for (final t in _terms) {
                  if (t['required'] == true) _agreed[t['id'] as int] = v ?? false;
                }
              }),
              title: const Text('필수 약관 전체 동의', style: TextStyle(fontWeight: FontWeight.w700)),
            ),
            Divider(color: c.line),
            for (final t in _terms)
              CheckboxListTile(
                contentPadding: EdgeInsets.zero,
                controlAffinity: ListTileControlAffinity.leading,
                value: _agreed[t['id'] as int] ?? false,
                onChanged: (v) => setState(() => _agreed[t['id'] as int] = v ?? false),
                title: Text('${t['required'] == true ? '(필수)' : '(선택)'} ${_label(t)}', style: const TextStyle(fontSize: 15)),
                secondary: TextButton(onPressed: () => infoDialog(context, _label(t), '${t['content']}'), child: const Text('보기')),
              ),
          ]),
        ),
        if (_err != null) AlertBox(_err!, tone: Tone.bad),
      ],
    );
  }
}
