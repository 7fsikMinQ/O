import 'package:bojohelp_app/main.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('첫 화면에 서비스명과 시간 안내 문구가 보인다', (tester) async {
    await tester.pumpWidget(const ProviderScope(child: BojoHelpApp()));
    expect(find.textContaining('보조출연 헬프'), findsOneWidget);
    expect(find.textContaining('예상'), findsOneWidget);
  });
}
