import 'package:dio/dio.dart';

/// 서버 오류. [message] 는 서버가 준 쉬운 말(없으면 상태별 기본 안내).
class ApiException implements Exception {
  ApiException(this.status, this.body);
  final int status;
  final Map<String, dynamic> body;

  String? get code => body['code'] as String?;

  String get message {
    final d = body['detail'];
    if (d is String && d.isNotEmpty && d != 'Bad Request' && d != 'Conflict' && d != 'Forbidden' && d != 'Not Found') return d;
    return switch (status) {
      401 => '로그인이 필요해요.',
      403 => '이 작업을 할 수 없어요.',
      404 => '찾을 수 없어요.',
      409 => '지금은 처리할 수 없는 상태예요.',
      410 => '시간이 지나 만료되었어요.',
      413 => '파일이 너무 커요. 5MB 이하로 올려 주세요.',
      429 => '요청이 너무 많아요. 잠시 뒤에 다시 해 주세요.',
      >= 500 => '서버에 문제가 생겼어요. 잠시 뒤에 다시 해 주세요.',
      0 => '인터넷 연결을 확인해 주세요.',
      _ => '요청에 실패했어요.',
    };
  }

  @override
  String toString() => 'ApiException($status)';
}

String errorText(Object e) => e is ApiException ? e.message : '문제가 생겼어요. 잠시 뒤에 다시 해 주세요.';

/// 토큰 저장소(앱은 보안 저장소, 시험은 메모리).
abstract class TokenStore {
  Future<({String? access, String? refresh})> read();
  Future<void> write(String? access, String? refresh);
}

class MemoryTokenStore implements TokenStore {
  String? _a, _r;
  @override
  Future<({String? access, String? refresh})> read() async => (access: _a, refresh: _r);
  @override
  Future<void> write(String? access, String? refresh) async {
    _a = access;
    _r = refresh;
  }
}

/// 백엔드 호출. 액세스 토큰이 만료되면 리프레시 토큰으로 한 번 갱신하고 다시 시도한다.
class Api {
  Api({required this.baseUrl, required this.store, Dio? dio})
      : _dio = dio ?? Dio(BaseOptions(baseUrl: baseUrl, connectTimeout: const Duration(seconds: 15), receiveTimeout: const Duration(seconds: 30), validateStatus: (_) => true));

  final String baseUrl;
  final TokenStore store;
  final Dio _dio;
  String? access;
  String? refresh;
  void Function()? onLoggedOut;
  Future<bool>? _refreshing;

  Future<void> load() async {
    final t = await store.read();
    access = t.access;
    refresh = t.refresh;
  }

  Future<void> setTokens(String? a, String? r) async {
    access = a;
    refresh = r;
    await store.write(a, r);
  }

  bool get hasSession => access != null;

  Future<dynamic> request(String method, String path, {Object? data, Map<String, dynamic>? query, bool retry = true}) async {
    Response<dynamic> res;
    try {
      res = await _dio.request<dynamic>(
        path,
        data: data,
        queryParameters: query,
        options: Options(method: method, headers: {if (access != null) 'Authorization': 'Bearer $access'}),
      );
    } on DioException {
      throw ApiException(0, const {});
    }
    final code = res.statusCode ?? 0;
    if (code == 401 && retry && refresh != null && await _tryRefresh()) return request(method, path, data: data, query: query, retry: false);
    if (code == 401 && access != null) {
      await setTokens(null, null);
      onLoggedOut?.call();
    }
    if (code >= 400) {
      final b = res.data;
      throw ApiException(code, b is Map<String, dynamic> ? b : const {});
    }
    return res.data;
  }

  Future<bool> _tryRefresh() {
    return _refreshing ??= () async {
      try {
        final res = await _dio.post<dynamic>('/auth/refresh', data: {'refreshToken': refresh});
        if ((res.statusCode ?? 0) != 200 || res.data is! Map) return false;
        final m = res.data as Map<String, dynamic>;
        await setTokens(m['accessToken'] as String, m['refreshToken'] as String?);
        return true;
      } on DioException {
        return false;
      } finally {
        _refreshing = null;
      }
    }();
  }

  Future<Map<String, dynamic>> getMap(String p, {Map<String, dynamic>? query}) async => Map<String, dynamic>.from(await request('GET', p, query: query) as Map);
  Future<List<Map<String, dynamic>>> getList(String p, {Map<String, dynamic>? query}) async =>
      ((await request('GET', p, query: query)) as List).map((e) => Map<String, dynamic>.from(e as Map)).toList();
  Future<Map<String, dynamic>> post(String p, [Object? data]) async {
    final r = await request('POST', p, data: data ?? <String, dynamic>{});
    return r is Map ? Map<String, dynamic>.from(r) : <String, dynamic>{};
  }

  Future<void> put(String p, [Object? data]) => request('PUT', p, data: data ?? <String, dynamic>{});
  Future<void> delete(String p, {Map<String, dynamic>? query}) => request('DELETE', p, query: query);

  /// 사진·서명 이미지 올리기.
  Future<Map<String, dynamic>> upload(String p, List<int> bytes, String filename, Map<String, String> fields) async {
    final form = FormData.fromMap({...fields, 'file': MultipartFile.fromBytes(bytes, filename: filename)});
    final r = await request('POST', p, data: form);
    return Map<String, dynamic>.from(r as Map);
  }

  /// 서버가 준 파일 주소(/files/…)를 전체 주소로.
  String fileUrl(String path) => path.startsWith('http') ? path : '$baseUrl$path';
}
