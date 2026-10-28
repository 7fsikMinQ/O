import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

import 'core/l10n/terms.dart';
import 'core/ui/tokens.dart';

void main() {
  runApp(const ProviderScope(child: BojoHelpApp()));
}

/// 뼈대: 테마(라이트/다크)와 첫 화면만. 화면 구성은 04·09번 문서(SCR-AU*, SCR-H*)를 따른다.
class BojoHelpApp extends StatelessWidget {
  const BojoHelpApp({super.key});

  ThemeData _theme(AppColors c, Brightness b) => ThemeData(
        useMaterial3: true,
        brightness: b,
        scaffoldBackgroundColor: c.bg,
        colorScheme: ColorScheme.fromSeed(seedColor: c.primary, brightness: b).copyWith(
          primary: c.primary,
          onPrimary: c.onPrimary,
          surface: c.surface,
        ),
        textTheme: TextTheme(
          bodyMedium: TextStyle(fontSize: AppSizes.body, color: c.text1),
          bodySmall: TextStyle(fontSize: AppSizes.caption, color: c.text3),
        ),
      );

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '보조출연 헬프',
      theme: _theme(AppColors.light, Brightness.light),
      darkTheme: _theme(AppColors.dark, Brightness.dark),
      themeMode: ThemeMode.system,
      home: const Scaffold(
        body: SafeArea(
          child: Center(child: Text('보조출연 헬프\n${Terms.timeNotice}', textAlign: TextAlign.center)),
        ),
      ),
    );
  }
}
