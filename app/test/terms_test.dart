import 'package:flutter_test/flutter_test.dart';
import 'package:bojohelp_app/core/l10n/terms.dart';

void main() {
  test('쉬운 말 용어 사전: 시스템 용어를 그대로 노출하지 않는다', () {
    expect(Terms.selected, isNot(contains('SELECTED')));
    expect(Terms.timeNotice, contains('예상'));
  });
}
