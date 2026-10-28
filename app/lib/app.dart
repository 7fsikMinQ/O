import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:material_ui/material_ui.dart';

import 'core/api.dart';
import 'core/l10n/terms.dart';
import 'core/session.dart';
import 'core/ui/tokens.dart';
import 'core/ui/widgets.dart';
import 'features/applications.dart';
import 'features/auth.dart';
import 'features/jobs.dart';
import 'features/messages.dart';
import 'features/myinfo.dart';

ThemeData buildTheme(AppColors c, Brightness b) => ThemeData(
      useMaterial3: true,
      brightness: b,
      scaffoldBackgroundColor: c.bg,
      colorScheme: ColorScheme.fromSeed(seedColor: c.primary, brightness: b).copyWith(primary: c.primary, onPrimary: c.onPrimary, surface: c.surface),
      textTheme: TextTheme(
        bodyMedium: TextStyle(fontSize: AppSizes.body, color: c.text1),
        bodyLarge: TextStyle(fontSize: AppSizes.body, color: c.text1),
        bodySmall: TextStyle(fontSize: AppSizes.caption, color: c.text3),
      ),
      navigationBarTheme: NavigationBarThemeData(
        backgroundColor: c.surface,
        indicatorColor: c.primaryWeak,
        height: 64,
        labelTextStyle: WidgetStateProperty.resolveWith((s) => TextStyle(fontSize: 12, fontWeight: s.contains(WidgetState.selected) ? FontWeight.w700 : FontWeight.w500, color: s.contains(WidgetState.selected) ? c.primaryText : c.text2)),
      ),
      snackBarTheme: SnackBarThemeData(contentTextStyle: TextStyle(color: c.bg)),
    );

/// 출연자 앱 루트. [session] 을 주입하면 시험에서 가짜 서버로 돌릴 수 있다.
class BojoHelpApp extends StatefulWidget {
  const BojoHelpApp({super.key, this.session});
  final Session? session;
  @override
  State<BojoHelpApp> createState() => _BojoHelpAppState();
}

class _BojoHelpAppState extends State<BojoHelpApp> {
  late final Session _session = widget.session ?? Session(Api(baseUrl: const String.fromEnvironment('API_BASE', defaultValue: 'http://localhost:8080'), store: SecureTokenStore()));
  bool _started = false;

  @override
  void initState() {
    super.initState();
    if (widget.session != null && widget.session!.ready) {
      _started = true;
    } else {
      _session.start(platform: kIsWeb ? 'web' : 'android').then((_) {
        if (mounted) setState(() => _started = true);
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return SessionScope(
      session: _session,
      child: MaterialApp(
        title: '보조출연 헬프',
        debugShowCheckedModeBanner: false,
        theme: buildTheme(AppColors.light, Brightness.light),
        darkTheme: buildTheme(AppColors.dark, Brightness.dark),
        themeMode: ThemeMode.system,
        routes: {'/notifications': (_) => const NotificationsScreen()},
        home: _started ? const _Root() : const _Splash(),
      ),
    );
  }
}

class _Splash extends StatelessWidget {
  const _Splash();
  @override
  Widget build(BuildContext context) => Scaffold(body: SafeArea(child: Center(child: Column(mainAxisSize: MainAxisSize.min, children: [const Text('🎬', style: TextStyle(fontSize: 56)), const SizedBox(height: 12), Text('보조출연 헬프', style: TextStyle(fontSize: 22, fontWeight: FontWeight.w800, color: colorsOf(context).primaryText))]))));
}

class _Root extends StatelessWidget {
  const _Root();
  @override
  Widget build(BuildContext context) {
    final s = SessionScope.of(context);
    if (s.updateRequired) return const UpdateRequiredScreen();
    if (!s.loggedIn) return const EntryScreen();
    return const MainShell();
  }
}

/// 아래 탭 4개: 홈 · 내 일정 · 메시지 · 내 정보.
class MainShell extends StatefulWidget {
  const MainShell({super.key});
  @override
  State<MainShell> createState() => _MainShellState();
}

class _MainShellState extends State<MainShell> {
  int _i = 0;
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      final s = SessionScope.of(context);
      s.refreshUnread();
      _timer = Timer.periodic(const Duration(seconds: 30), (_) => s.refreshUnread());
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    const pages = [HomeScreen(), MyScheduleScreen(), MessagesScreen(), MyInfoScreen()];
    return Scaffold(
      body: IndexedStack(index: _i, children: [for (var k = 0; k < pages.length; k++) _i == k ? pages[k] : const SizedBox.shrink()]),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _i,
        onDestinationSelected: (v) => setState(() => _i = v),
        destinations: const [
          NavigationDestination(icon: Text('🏠', style: TextStyle(fontSize: 22)), label: '홈'),
          NavigationDestination(icon: Text('📅', style: TextStyle(fontSize: 22)), label: '내 일정'),
          NavigationDestination(icon: Text('💬', style: TextStyle(fontSize: 22)), label: '메시지'),
          NavigationDestination(icon: Text('🙂', style: TextStyle(fontSize: 22)), label: '내 정보'),
        ],
      ),
    );
  }
}

/// 최소 버전 미만이면 이 화면만 보인다(앱 설정 /app/config).
class UpdateRequiredScreen extends StatelessWidget {
  const UpdateRequiredScreen({super.key});
  @override
  Widget build(BuildContext context) => const AppPage(
        title: '⬆️ 업데이트가 필요해요',
        back: false,
        subtitle: '새 버전으로 바뀌어야 계속 쓸 수 있어요.',
        children: [AlertBox('더 안전하고 빨라졌어요. 스토어(또는 웹은 새로고침)에서 업데이트해 주세요.'), AlertBox(Terms.timeNotice, tone: Tone.ok)],
      );
}
