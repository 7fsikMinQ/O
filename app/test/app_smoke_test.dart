import 'package:bojohelp_app/app.dart';
import 'package:bojohelp_app/core/api.dart';
import 'package:bojohelp_app/core/session.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('로그인 전에는 시작하기 화면이 보이고 시간 안내 문구가 앱 안에 있다', (tester) async {
    final session = Session(Api(baseUrl: 'http://127.0.0.1:1', store: MemoryTokenStore()));
    session.ready = true;
    await tester.pumpWidget(BojoHelpApp(session: session));
    await tester.pump();
    expect(find.textContaining('시작하기'), findsWidgets);
    expect(find.textContaining('인증번호'), findsWidgets);
  });
}
