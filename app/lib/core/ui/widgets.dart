import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import '../api.dart';
import 'tokens.dart';

AppColors colorsOf(BuildContext context) => Theme.of(context).brightness == Brightness.dark ? AppColors.dark : AppColors.light;

/// 주 버튼: 높이 52(터치 44 이상), 쉬운 말 한 줄. 눌린 동안 [busy] 로 중복 전송을 막는다.
class PrimaryButton extends StatelessWidget {
  const PrimaryButton(this.label, {super.key, this.onPressed, this.busy = false, this.tone});
  final String label;
  final VoidCallback? onPressed;
  final bool busy;
  final Color? tone;

  @override
  Widget build(BuildContext context) {
    final c = colorsOf(context);
    return SizedBox(
      width: double.infinity,
      height: AppSizes.buttonHeight,
      child: FilledButton(
        style: FilledButton.styleFrom(
          backgroundColor: tone ?? c.primary,
          foregroundColor: c.onPrimary,
          disabledBackgroundColor: c.primary.withValues(alpha: .35),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
          textStyle: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700),
        ),
        onPressed: busy ? null : onPressed,
        child: busy ? const SizedBox(width: 22, height: 22, child: CircularProgressIndicator(strokeWidth: 2.5)) : Text(label, textAlign: TextAlign.center),
      ),
    );
  }
}

class LineButton extends StatelessWidget {
  const LineButton(this.label, {super.key, this.onPressed, this.bad = false});
  final String label;
  final VoidCallback? onPressed;
  final bool bad;

  @override
  Widget build(BuildContext context) {
    final c = colorsOf(context);
    final fg = bad ? c.badText : c.primaryText;
    return SizedBox(
      width: double.infinity,
      height: AppSizes.buttonHeight,
      child: OutlinedButton(
        style: OutlinedButton.styleFrom(
          foregroundColor: fg,
          side: BorderSide(color: bad ? c.badText : c.primary, width: 1.5),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
          textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
        ),
        onPressed: onPressed,
        child: Text(label, textAlign: TextAlign.center),
      ),
    );
  }
}

class TextLink extends StatelessWidget {
  const TextLink(this.label, {super.key, this.onPressed});
  final String label;
  final VoidCallback? onPressed;
  @override
  Widget build(BuildContext context) => SizedBox(
        height: AppSizes.minTouch,
        child: TextButton(onPressed: onPressed, style: TextButton.styleFrom(foregroundColor: colorsOf(context).primaryText, textStyle: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600)), child: Text(label)),
      );
}

class AppCard extends StatelessWidget {
  const AppCard({super.key, required this.child, this.onTap, this.padding = const EdgeInsets.all(16), this.tone});
  final Widget child;
  final VoidCallback? onTap;
  final EdgeInsets padding;
  final Color? tone;

  @override
  Widget build(BuildContext context) {
    final c = colorsOf(context);
    final shape = RoundedRectangleBorder(borderRadius: BorderRadius.circular(16), side: BorderSide(color: c.line));
    return SizedBox(
      width: double.infinity,
      child: Material(
        color: tone ?? c.surface,
        shape: shape,
        clipBehavior: Clip.antiAlias,
        child: onTap == null
            ? Padding(padding: padding, child: child)
            : InkWell(onTap: onTap, child: Padding(padding: padding, child: child)),
      ),
    );
  }
}

enum Tone { normal, ok, warn, bad }

class Pill extends StatelessWidget {
  const Pill(this.label, {super.key, this.tone = Tone.normal});
  final String label;
  final Tone tone;
  @override
  Widget build(BuildContext context) {
    final c = colorsOf(context);
    final (bg, fg) = switch (tone) {
      Tone.ok => (c.okBg, c.okText),
      Tone.warn => (c.warnBg, c.warnText),
      Tone.bad => (c.badBg, c.badText),
      Tone.normal => (c.primaryWeak, c.primaryText),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(999)),
      child: Text(label, style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: fg)),
    );
  }
}

class AlertBox extends StatelessWidget {
  const AlertBox(this.text, {super.key, this.tone = Tone.warn});
  final String text;
  final Tone tone;
  @override
  Widget build(BuildContext context) {
    final c = colorsOf(context);
    final (bg, fg) = switch (tone) {
      Tone.ok => (c.okBg, c.okText),
      Tone.bad => (c.badBg, c.badText),
      _ => (c.warnBg, c.warnText),
    };
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(12)),
      child: Text(text, style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: fg, height: 1.4)),
    );
  }
}

/// 라벨이 위에 있는 입력 칸. 키보드가 올라와도 가려지지 않게 화면이 스크롤된다.
class LabeledField extends StatelessWidget {
  const LabeledField({super.key, required this.label, required this.controller, this.hint, this.keyboard, this.maxLines = 1, this.obscure = false, this.formatters, this.maxLength, this.onChanged});
  final String label;
  final TextEditingController controller;
  final String? hint;
  final TextInputType? keyboard;
  final int maxLines;
  final bool obscure;
  final List<TextInputFormatter>? formatters;
  final int? maxLength;
  final ValueChanged<String>? onChanged;

  @override
  Widget build(BuildContext context) {
    final c = colorsOf(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: c.text2)),
        const SizedBox(height: 6),
        TextField(
          controller: controller,
          keyboardType: keyboard,
          maxLines: maxLines,
          maxLength: maxLength,
          obscureText: obscure,
          inputFormatters: formatters,
          onChanged: onChanged,
          style: TextStyle(fontSize: AppSizes.body, color: c.text1),
          decoration: InputDecoration(
            hintText: hint,
            counterText: '',
            filled: true,
            fillColor: c.surface,
            contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
            border: OutlineInputBorder(borderRadius: BorderRadius.circular(12), borderSide: BorderSide(color: c.line)),
            enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(12), borderSide: BorderSide(color: c.line)),
            focusedBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(12), borderSide: BorderSide(color: c.primary, width: 2)),
          ),
        ),
      ],
    );
  }
}

/// 화면 공통 틀: 제목(22 굵게) + 본문 스크롤 + 아래 고정 버튼 영역(본문을 가리지 않음).
class AppPage extends StatelessWidget {
  const AppPage({super.key, required this.title, required this.children, this.bottom, this.actions, this.onRefresh, this.back = true, this.subtitle});
  final String title;
  final String? subtitle;
  final List<Widget> children;
  final Widget? bottom;
  final List<Widget>? actions;
  final Future<void> Function()? onRefresh;
  final bool back;

  @override
  Widget build(BuildContext context) {
    final c = colorsOf(context);
    final list = ListView(
      padding: const EdgeInsets.fromLTRB(AppSizes.pagePadding, 4, AppSizes.pagePadding, 24),
      physics: const AlwaysScrollableScrollPhysics(),
      children: [
        if (subtitle != null) Padding(padding: const EdgeInsets.only(bottom: 12), child: Text(subtitle!, style: TextStyle(fontSize: 14, color: c.text2, height: 1.4))),
        for (final w in children) Padding(padding: const EdgeInsets.only(bottom: 12), child: w),
      ],
    );
    return Scaffold(
      backgroundColor: c.bg,
      appBar: AppBar(
        backgroundColor: c.bg,
        elevation: 0,
        scrolledUnderElevation: 0,
        automaticallyImplyLeading: false,
        leading: back && Navigator.of(context).canPop() ? IconButton(tooltip: '뒤로', onPressed: () => Navigator.of(context).maybePop(), icon: Text('←', style: TextStyle(fontSize: 22, color: c.primaryText))) : null,
        titleSpacing: back && Navigator.of(context).canPop() ? 0 : AppSizes.pagePadding,
        title: Text(title, style: TextStyle(fontSize: 22, fontWeight: FontWeight.w700, color: c.text1)),
        actions: actions,
      ),
      body: SafeArea(top: false, child: onRefresh == null ? list : RefreshIndicator(onRefresh: onRefresh!, child: list)),
      bottomNavigationBar: bottom == null
          ? null
          : SafeArea(
              child: Container(
                padding: const EdgeInsets.fromLTRB(AppSizes.pagePadding, 12, AppSizes.pagePadding, 12),
                decoration: BoxDecoration(color: c.bg, border: Border(top: BorderSide(color: c.line))),
                child: bottom,
              ),
            ),
    );
  }
}

void toast(BuildContext context, String message, {bool bad = false}) {
  final c = colorsOf(context);
  ScaffoldMessenger.of(context)
    ..hideCurrentSnackBar()
    ..showSnackBar(SnackBar(content: Text(message, style: const TextStyle(fontSize: 15)), backgroundColor: bad ? c.badText : c.text1, behavior: SnackBarBehavior.floating, duration: const Duration(seconds: 4)));
}

/// 확인 창(쉬운 말, 버튼 두 개).
Future<bool> confirmDialog(BuildContext context, String title, String body, {String yes = '네', String no = '아니요'}) async {
  final r = await showDialog<bool>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: Text(title),
      content: Text(body, style: const TextStyle(fontSize: 15, height: 1.5)),
      actions: [TextButton(onPressed: () => Navigator.pop(ctx, false), child: Text(no)), TextButton(onPressed: () => Navigator.pop(ctx, true), child: Text(yes))],
    ),
  );
  return r ?? false;
}

Future<void> infoDialog(BuildContext context, String title, String body) => showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(title: Text(title), content: Text(body, style: const TextStyle(fontSize: 15, height: 1.5)), actions: [TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('확인'))]),
    );

/// 불러오기 + 다시 시도. [builder] 는 데이터와 새로고침 함수를 받는다.
class AsyncView<T> extends StatefulWidget {
  const AsyncView({super.key, required this.load, required this.builder});
  final Future<T> Function() load;
  final Widget Function(BuildContext context, T data, Future<void> Function() reload) builder;
  @override
  State<AsyncView<T>> createState() => _AsyncViewState<T>();
}

class _AsyncViewState<T> extends State<AsyncView<T>> {
  late Future<T> _f = widget.load();

  Future<void> reload() async {
    final f = widget.load();
    setState(() => _f = f);
    try {
      await f;
    } catch (_) {}
  }

  @override
  Widget build(BuildContext context) => FutureBuilder<T>(
        future: _f,
        builder: (context, snap) {
          if (snap.connectionState != ConnectionState.done) return const Padding(padding: EdgeInsets.all(48), child: Center(child: CircularProgressIndicator()));
          if (snap.hasError) {
            return Padding(
              padding: const EdgeInsets.all(24),
              child: Column(mainAxisSize: MainAxisSize.min, children: [
                AlertBox(errorText(snap.error!), tone: Tone.bad),
                const SizedBox(height: 12),
                LineButton('다시 해 볼게요', onPressed: reload),
              ]),
            );
          }
          return widget.builder(context, snap.data as T, reload);
        },
      );
}

/// 화면 한 장을 불러오는 경우: 제목·새로고침이 있는 페이지.
class LoadingPage<T> extends StatelessWidget {
  const LoadingPage({super.key, required this.title, required this.load, required this.builder, this.bottomBuilder, this.actions, this.subtitle});
  final String title;
  final String? subtitle;
  final Future<T> Function() load;
  final List<Widget> Function(BuildContext context, T data, Future<void> Function() reload) builder;
  final Widget? Function(BuildContext context, T data, Future<void> Function() reload)? bottomBuilder;
  final List<Widget>? actions;

  @override
  Widget build(BuildContext context) {
    return AsyncView<T>(
      load: load,
      builder: (context, data, reload) => AppPage(
        title: title,
        subtitle: subtitle,
        actions: actions,
        onRefresh: reload,
        bottom: bottomBuilder?.call(context, data, reload),
        children: builder(context, data, reload),
      ),
    );
  }
}

/// 빈 상태 안내.
class EmptyNote extends StatelessWidget {
  const EmptyNote(this.text, {super.key});
  final String text;
  @override
  Widget build(BuildContext context) => Padding(padding: const EdgeInsets.symmetric(vertical: 32), child: Center(child: Text(text, textAlign: TextAlign.center, style: TextStyle(fontSize: 15, color: colorsOf(context).text2, height: 1.5))));
}

class SectionTitle extends StatelessWidget {
  const SectionTitle(this.text, {super.key});
  final String text;
  @override
  Widget build(BuildContext context) => Text(text, style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: colorsOf(context).text1));
}

class KeyValue extends StatelessWidget {
  const KeyValue(this.k, this.v, {super.key});
  final String k;
  final String v;
  @override
  Widget build(BuildContext context) {
    final c = colorsOf(context);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        SizedBox(width: 92, child: Text(k, style: TextStyle(fontSize: 14, color: c.text3))),
        Expanded(child: Text(v, style: TextStyle(fontSize: 15, color: c.text1, height: 1.4))),
      ]),
    );
  }
}
