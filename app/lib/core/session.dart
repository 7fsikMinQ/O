import 'package:flutter/widgets.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import 'api.dart';

class SecureTokenStore implements TokenStore {
  static const _s = FlutterSecureStorage();
  @override
  Future<({String? access, String? refresh})> read() async {
    try {
      return (access: await _s.read(key: 'access'), refresh: await _s.read(key: 'refresh'));
    } catch (_) {
      return (access: null, refresh: null);
    }
  }

  @override
  Future<void> write(String? access, String? refresh) async {
    try {
      if (access == null) {
        await _s.delete(key: 'access');
        await _s.delete(key: 'refresh');
      } else {
        await _s.write(key: 'access', value: access);
        if (refresh != null) await _s.write(key: 'refresh', value: refresh);
      }
    } catch (_) {/* 저장소를 못 쓰는 환경에서도 메모리 토큰으로 동작 */}
  }
}

/// 로그인 상태·내 정보·앱 설정. 화면은 [SessionScope] 로 읽는다.
class Session extends ChangeNotifier {
  Session(this.api) {
    api.onLoggedOut = () {
      me = null;
      notifyListeners();
    };
  }
  final Api api;
  Map<String, dynamic>? me;
  Map<String, dynamic> config = const {'otpChannel': 'email', 'updateRequired': false};
  bool ready = false;
  int unread = 0;

  bool get loggedIn => api.hasSession && me != null;
  String get otpChannel => (config['otpChannel'] as String?) ?? 'email';
  bool get updateRequired => config['updateRequired'] == true;

  Future<void> start({String platform = 'android', String version = '1.0.0'}) async {
    await api.load();
    try {
      config = await api.getMap('/app/config', query: {'platform': platform, 'version': version});
    } catch (_) {/* 서버에 못 닿아도 로그인 화면은 보여 준다 */}
    if (api.hasSession) {
      try {
        me = await api.getMap('/me');
      } catch (_) {
        me = null;
      }
    }
    ready = true;
    notifyListeners();
  }

  Future<void> loginWith(Map<String, dynamic> tokens) async {
    await api.setTokens(tokens['accessToken'] as String, tokens['refreshToken'] as String?);
    me = await api.getMap('/me');
    notifyListeners();
  }

  Future<void> refreshMe() async {
    me = await api.getMap('/me');
    notifyListeners();
  }

  Future<void> refreshUnread() async {
    try {
      unread = (await api.getMap('/notifications/unread-count'))['count'] as int;
      notifyListeners();
    } catch (_) {}
  }

  Future<void> logout() async {
    try {
      if (api.refresh != null) await api.post('/auth/logout', {'refreshToken': api.refresh});
    } catch (_) {}
    await api.setTokens(null, null);
    me = null;
    notifyListeners();
  }
}

class SessionScope extends InheritedNotifier<Session> {
  const SessionScope({super.key, required Session session, required super.child}) : super(notifier: session);
  static Session of(BuildContext context) => context.dependOnInheritedWidgetOfExactType<SessionScope>()!.notifier!;

  /// 변경을 구독하지 않고 읽기만(initState 처럼 구독이 안 되는 곳에서).
  static Session read(BuildContext context) => context.getInheritedWidgetOfExactType<SessionScope>()!.notifier!;
}
