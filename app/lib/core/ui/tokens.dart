import 'dart:ui';

/// 디자인 토큰(15번 문서·docs/mockups/tokens.css 와 동일). 화면에서 색을 직접 쓰지 말고 여기서만 가져온다.
class AppColors {
  const AppColors._({
    required this.bg,
    required this.surface,
    required this.primary,
    required this.onPrimary,
    required this.primaryWeak,
    required this.primaryText,
    required this.text1,
    required this.text2,
    required this.text3,
    required this.okText,
    required this.okBg,
    required this.warnText,
    required this.warnBg,
    required this.badText,
    required this.badBg,
    required this.line,
  });

  final Color bg, surface, primary, onPrimary, primaryWeak, primaryText;
  final Color text1, text2, text3, okText, okBg, warnText, warnBg, badText, badBg, line;

  static const light = AppColors._(
    bg: Color(0xFFFAF9FF),
    surface: Color(0xFFFFFFFF),
    primary: Color(0xFF6F5BD6),
    onPrimary: Color(0xFFFFFFFF),
    primaryWeak: Color(0xFFF1EDFF),
    primaryText: Color(0xFF5B47C2),
    text1: Color(0xFF191F28),
    text2: Color(0xFF4E5968),
    text3: Color(0xFF5B6677),
    okText: Color(0xFF14633F),
    okBg: Color(0xFFDDF7EA),
    warnText: Color(0xFFA3470F),
    warnBg: Color(0xFFFFEBDD),
    badText: Color(0xFFB3261E),
    badBg: Color(0xFFFFE3E0),
    line: Color(0xFFE8E6F2),
  );

  static const dark = AppColors._(
    bg: Color(0xFF171520),
    surface: Color(0xFF221F30),
    primary: Color(0xFFB7A9FF),
    onPrimary: Color(0xFF1A1530),
    primaryWeak: Color(0xFF2F2A4D),
    primaryText: Color(0xFFD8D0FF),
    text1: Color(0xFFF2F0FF),
    text2: Color(0xFFB9B4CF),
    text3: Color(0xFF8F8AA6),
    okText: Color(0xFF7FE0B5),
    okBg: Color(0xFF1F3B33),
    warnText: Color(0xFFFFB38F),
    warnBg: Color(0xFF4A3226),
    badText: Color(0xFFFF9C95),
    badBg: Color(0xFF4A2A2A),
    line: Color(0xFF352F4D),
  );
}

/// 크기 기준(19번 문서): 본문 16 · 보조 13 · 최소 12, 주 버튼 52 · 최소 터치 44.
class AppSizes {
  const AppSizes._();
  static const double body = 16;
  static const double caption = 13;
  static const double minText = 12;
  static const double buttonHeight = 52;
  static const double minTouch = 44;
  static const double pagePadding = 16;
}
