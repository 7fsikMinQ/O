import 'package:bojohelp_app/app.dart';
import 'package:bojohelp_app/core/session.dart';
import 'package:bojohelp_app/core/ui/tokens.dart';
import 'package:bojohelp_app/features/applications.dart';
import 'package:bojohelp_app/features/apply.dart';
import 'package:bojohelp_app/features/auth.dart';
import 'package:bojohelp_app/features/jobs.dart';
import 'package:bojohelp_app/features/messages.dart';
import 'package:bojohelp_app/features/myinfo.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_test/flutter_test.dart';

import 'fake_api.dart';

/// 화면이 모든 폰 폭·큰 글씨·라이트/다크에서 넘치거나 겹쳐 그려지지 않는지(레이아웃 오류 없음) 점검한다.
/// 시험 환경 글꼴(Ahem)은 실제보다 넓어서 오히려 더 엄격하다.
const sizes = [Size(320, 640), Size(360, 740), Size(412, 860)];
const scales = [1.0, 1.3];

Future<void> pump(WidgetTester t, Widget screen, {required Size size, required double scale, required bool dark, Session? session}) async {
  t.view.physicalSize = size;
  t.view.devicePixelRatio = 1;
  addTearDown(t.view.reset);
  final s = session ?? fakeSession();
  await t.pumpWidget(
    SessionScope(
      session: s,
      child: MediaQuery(
        data: MediaQueryData(size: size, textScaler: TextScaler.linear(scale)),
        child: MaterialApp(
          debugShowCheckedModeBanner: false,
          theme: buildTheme(AppColors.light, Brightness.light),
          darkTheme: buildTheme(AppColors.dark, Brightness.dark),
          themeMode: dark ? ThemeMode.dark : ThemeMode.light,
          routes: {'/notifications': (_) => const NotificationsScreen()},
          home: screen,
        ),
      ),
    ),
  );
  for (var i = 0; i < 6; i++) {
    await t.pump(const Duration(milliseconds: 60));
  }
}

void main() {
  final screens = <String, Widget Function()>{
    '시작하기': () => const EntryScreen(),
    '인증번호': () => const CodeScreen(identifier: 'name@example.com'),
    '가입': () => const SignupScreen(signupToken: 'x'),
    '홈(목록)': () => const HomeScreen(),
    '공고 상세': () => const JobDetailScreen(postingId: 5),
    '지원 1단계': () => const ApplyFlow(positionId: 11, roleTitle: '저잣거리 행인'),
    '내 일정': () => const MyScheduleScreen(),
    '지원 상세(뽑힘)': () => const ApplicationDetailScreen(applicationId: 21),
    '일정 겹침': () => const ConflictsScreen(),
    '합격서': () => const OfferScreen(applicationId: 21),
    '출근 QR': () => const QrScreen(applicationId: 21),
    '메시지함': () => const MessagesScreen(),
    '대화': () => const ThreadScreen(threadId: 1),
    '알림': () => const NotificationsScreen(),
    '내 정보': () => const MyInfoScreen(),
    '내 프로필': () => const ProfileEditScreen(),
    '주민번호': () => const RrnScreen(),
    '정산 계좌': () => const PayoutScreen(),
    '알림 설정': () => const SettingsScreen(),
    '약관': () => const TermsScreen(),
    '업데이트 필요': () => const UpdateRequiredScreen(),
  };

  for (final e in screens.entries) {
    for (final size in sizes) {
      for (final scale in scales) {
        for (final dark in (size.width == 360 && scale == 1.0 ? [false, true] : [false])) {
          testWidgets('${e.key} · ${size.width.toInt()}px · 글씨 $scale배 · ${dark ? '다크' : '라이트'}', (tester) async {
            final errors = <FlutterErrorDetails>[];
            final old = FlutterError.onError;
            FlutterError.onError = errors.add;
            await pump(tester, e.value(), size: size, scale: scale, dark: dark);
            await tester.pumpWidget(const SizedBox.shrink());
            await tester.pump(const Duration(seconds: 31));
            FlutterError.onError = old;
            final thrown = tester.takeException();
            if (thrown != null) errors.add(FlutterErrorDetails(exception: thrown));
            final text = errors.map((x) => x.exceptionAsString().split('\n').first).toSet().join(' | ');
            expect(errors, isEmpty, reason: text);
          });
        }
      }
    }
  }

  testWidgets('지원하기 전체 단계(3단계)가 모든 폭에서 넘치지 않는다', (tester) async {
    final problems = <String>[];
    for (final size in sizes) {
      for (final scale in scales) {
        final errors = <FlutterErrorDetails>[];
        final old = FlutterError.onError;
        FlutterError.onError = errors.add;
        try {
          await tester.pumpWidget(const SizedBox.shrink());
          await pump(tester, const ApplyFlow(positionId: 11, roleTitle: '저잣거리 행인'), size: size, scale: scale, dark: false);
          await tester.tap(find.textContaining('다음: 내 사진'), warnIfMissed: false);
          await tester.pump(const Duration(milliseconds: 100));
        } finally {
          FlutterError.onError = old;
        }
        final thrown = tester.takeException();
        if (thrown != null) problems.add('${size.width.toInt()}px/$scale: $thrown');
        problems.addAll(errors.map((x) => '${size.width.toInt()}px/$scale: ${x.exceptionAsString().split('\n').first}'));
      }
    }
    expect(problems, isEmpty);
  });

  testWidgets('이메일 채널이면 이메일 칸, 문자 채널이면 번호 칸이 나온다', (tester) async {
    final api = FakeApi();
    final s = fakeSession(api);
    await pump(tester, const EntryScreen(), size: sizes[1], scale: 1, dark: false, session: s);
    expect(find.text('이메일'), findsOneWidget);
    s.config = {'otpChannel': 'sms', 'updateRequired': false};
    await pump(tester, const EntryScreen(), size: sizes[1], scale: 1, dark: false, session: s);
    expect(find.text('휴대폰 번호'), findsOneWidget);
  });

  testWidgets('합격서에는 금액이 없고 근로계약서가 아니라는 안내가 있다', (tester) async {
    await pump(tester, const OfferScreen(applicationId: 21), size: sizes[1], scale: 1, dark: false);
    expect(find.textContaining('근로계약서는 현장에서'), findsOneWidget);
    expect(find.textContaining('10,320'), findsNothing);
    expect(find.textContaining('원'), findsNothing);
  });

  testWidgets('시간은 예상이라는 안내가 공고 상세에 항상 보인다', (tester) async {
    await pump(tester, const JobDetailScreen(postingId: 5), size: sizes[1], scale: 1, dark: false);
    expect(find.textContaining('예상'), findsWidgets);
    expect(find.textContaining('비공개 현장'), findsOneWidget);
  });
}
