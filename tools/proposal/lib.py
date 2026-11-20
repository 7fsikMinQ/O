# -*- coding: utf-8 -*-
"""제안서용 화면 시안 부품. 33번(UI 디자인 시스템 v2) 토큰을 그대로 쓴다."""
import html as _h

def e(s):
    return _h.escape(str(s), quote=True)

ICONS = {
 'cal':'<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M16 3v4M8 3v4M3 11h18"/>',
 'home':'<path d="M3 11l9-8 9 8M5 10v10h14V10"/>',
 'users':'<circle cx="9" cy="8" r="3.5"/><path d="M2.5 20c.6-3.6 3.2-5.5 6.5-5.5s5.9 1.9 6.5 5.5M16 4.8a3.5 3.5 0 010 6.4M18 14.8c2 .6 3.2 2.3 3.5 5.2"/>',
 'shield':'<path d="M12 3l8 3v6c0 4.5-3.2 7.8-8 9-4.8-1.2-8-4.5-8-9V6z"/><path d="M9 12l2 2 4-4"/>',
 'log':'<path d="M6 3h9l4 4v14H6z"/><path d="M14 3v5h5M9 13h6M9 17h6"/>',
 'trash':'<path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13"/>',
 'plus':'<path d="M12 5v14M5 12h14"/>',
 'chev':'<path d="M9 6l6 6-6 6"/>',
 'down':'<path d="M6 9l6 6 6-6"/>',
 'check':'<path d="M5 12.5l4.5 4.5L19 7.5"/>',
 'search':'<circle cx="11" cy="11" r="6.5"/><path d="M16 16l4.5 4.5"/>',
 'user':'<circle cx="12" cy="8" r="4"/><path d="M4 21c.8-4 4-6 8-6s7.2 2 8 6"/>',
 'qr':'<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><path d="M14 14h3v3h-3zM20 14v.01M14 20h3M20 17v4"/>',
 'bell':'<path d="M6 17V11a6 6 0 0112 0v6l1.5 2h-15zM10 21h4"/>',
 'phone':'<path d="M5 4h4l2 5-2.5 1.5a11 11 0 005 5L15 13l5 2v4a2 2 0 01-2 2A15 15 0 013 6a2 2 0 012-2z"/>',
 'msg':'<path d="M4 5h16v11H9l-5 4z"/>',
 'film':'<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M3 9h18M3 15h18M8 4v16M16 4v16"/>',
 'play':'<path d="M8 5l11 7-11 7z"/>',
 'cog':'<circle cx="12" cy="12" r="3"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3M5 5l2 2M17 17l2 2M19 5l-2 2M7 17l-2 2"/>',
 'box':'<path d="M3 7l9-4 9 4v10l-9 4-9-4zM3 7l9 4 9-4M12 11v10"/>',
 'chart':'<path d="M4 20V4M4 20h16M8 16v-5M12 16V8M16 16v-3"/>',
 'file':'<path d="M6 3h9l4 4v14H6z"/><path d="M14 3v5h5"/>',
 'cam':'<path d="M4 8h3l2-3h6l2 3h3v12H4z"/><circle cx="12" cy="13" r="3.5"/>',
 'back':'<path d="M15 6l-6 6 6 6"/>',
 'x':'<path d="M6 6l12 12M18 6L6 18"/>',
 'lock':'<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V8a4 4 0 018 0v3"/>',
 'star':'<path d="M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1-5.4-2.9-5.4 2.9 1-6.1L3.2 9.5l6.1-.9z"/>',
 'map':'<path d="M12 21s7-6 7-11a7 7 0 10-14 0c0 5 7 11 7 11z"/><circle cx="12" cy="10" r="2.5"/>',
 'edit':'<path d="M4 20h4L19 9l-4-4L4 16zM14 6l4 4"/>',
 'upload':'<path d="M12 16V4M7 9l5-5 5 5M4 20h16"/>',
}
def ic(name, size=20, color=None):
    st = f' style="width:{size}px;height:{size}px;{("color:"+color) if color else ""}"'
    return f'<svg class="i"{st} viewBox="0 0 24 24">{ICONS[name]}</svg>'

def badge(t, k='gray'):
    return f'<span class="badge b-{k}">{e(t)}</span>'
def btn(t, k='', icon=None, style=''):
    cls = 'btn' + (' '+k if k else '')
    return f'<span class="{cls}" style="{style}">{ic(icon,16) if icon else ""}{e(t)}</span>'
def field(label, val='', state='', hint='', w=None, ph=False):
    cls = 'input' + (' '+state if state else '') + (' ph' if ph else '')
    st = f' style="width:{w}px"' if w else ''
    h = f'<div class="cap">{e(hint)}</div>' if hint and state!='err' else ''
    er = f'<div class="errmsg">{e(hint)}</div>' if state=='err' and hint else ''
    return f'<div class="field"{st}><label>{e(label)}</label><div class="{cls}">{e(val)}</div>{er}{h}</div>'
def select(label, val, w=None, ph=False):
    cls = 'input sel' + (' ph' if ph else '')
    st = f' style="width:{w}px"' if w else ''
    return f'<div class="field"{st}><label>{e(label)}</label><div class="{cls}"><span>{e(val)}</span>{ic("down",16)}</div></div>'
def textarea(label, val, h=72):
    return f'<div class="field"><label>{e(label)}</label><div class="input ta" style="height:{h}px">{e(val)}</div></div>'
def radios(label, opts, sel=0):
    s = ''.join(f'<div class="radio"><i class="{"on" if i==sel else ""}"></i>{e(o)}</div>' for i,o in enumerate(opts))
    return f'<div class="field"><label>{e(label)}</label>{s}</div>'
def checks(items, cols=None):
    out=''
    for it in items:
        t, on = it[0], it[1]
        warn = len(it)>2 and it[2]
        out += f'<span class="ck{" warn" if warn else ""}"><i class="{"on" if on else ""}">{ic("check",13) if on else ""}</i>{e(t)}</span>'
    return f'<div class="checks">{out}</div>'
def switch(on):
    return f'<span class="sw2 {"on" if on else ""}"><i></i></span>'
def alert(t, k='warn'):
    return f'<div class="alert a-{k}">{e(t)}</div>'
def stat(n, l):
    return f'<div class="card stat"><div class="n tnum">{e(n)}</div><div class="l">{e(l)}</div></div>'
def chips(items, on=0):
    def one(i, t):
        arrow = t.endswith('▾'); t = t.replace('▾','').strip()
        return f'<span class="chip{" on" if i==on else ""}">{e(t)}{ic("down",14) if arrow else ""}</span>'
    return '<div class="filters">'+''.join(one(i,t) for i,t in enumerate(items))+'</div>'
def tabs(items, on=0):
    return '<div class="tabs">'+''.join(f'<span class="{"on" if i==on else ""}">{e(t)}</span>' for i,t in enumerate(items))+'</div>'
def card(body, pad=20, style=''):
    return f'<div class="card" style="padding:{pad}px;{style}">{body}</div>'
def kv(pairs):
    return '<div class="kv">'+''.join(f'<div class="k">{e(k)}</div><div class="v">{v}</div>' for k,v in pairs)+'</div>'
def table(heads, rows, aligns=None, widths=None):
    aligns = aligns or ['']*len(heads)
    th = ''.join(f'<th class="{a}">{e(h)}</th>' for h,a in zip(heads,aligns))
    tb = ''
    for r in rows:
        tb += '<tr>'+''.join(f'<td class="{a}">{c}</td>' for c,a in zip(r,aligns))+'</tr>'
    return f'<table><thead><tr>{th}</tr></thead><tbody>{tb}</tbody></table>'
def h(t, sz=22):
    return f'<h1 style="font-size:{sz}px;line-height:{sz+8}px;margin:0">{e(t)}</h1>'
def page_head(title, actions=''):
    return f'<div class="head">{h(title)}<div class="row">{actions}</div></div>'
def avatar(name, size=32):
    return f'<span class="av" style="width:{size}px;height:{size}px;font-size:{size//2.4:.0f}px">{e(name[:1])}</span>'
def photo(w, hh, label=''):
    return f'<div class="photo" style="width:{w}px;height:{hh}px">{ic("user", min(w,hh)//2)}<span>{e(label)}</span></div>'
def video(w, hh, dur='1:20', label=''):
    return f'<div class="photo vid" style="width:{w}px;height:{hh}px"><span class="pl">{ic("play",22)}</span><span class="dur">{e(dur)}</span><span class="lb">{e(label)}</span></div>'
def qr(size=170):
    import random
    rnd = random.Random(7); n=21; c=size/n; cells=''
    def finder(x,y):
        return (f'<rect x="{x*c}" y="{y*c}" width="{7*c}" height="{7*c}" fill="#191F28"/>'
                f'<rect x="{(x+1)*c}" y="{(y+1)*c}" width="{5*c}" height="{5*c}" fill="#fff"/>'
                f'<rect x="{(x+2)*c}" y="{(y+2)*c}" width="{3*c}" height="{3*c}" fill="#191F28"/>')
    for yy in range(n):
        for xx in range(n):
            if (xx<8 and yy<8) or (xx>n-9 and yy<8) or (xx<8 and yy>n-9): continue
            if rnd.random()<.5: cells += f'<rect x="{xx*c:.1f}" y="{yy*c:.1f}" width="{c:.1f}" height="{c:.1f}" fill="#191F28"/>'
    return f'<svg width="{size}" height="{size}" viewBox="0 0 {size} {size}" style="background:#fff;border:1px solid #E5E8EB;border-radius:8px;padding:8px;box-sizing:content-box"><g>{cells}{finder(0,0)}{finder(n-7,0)}{finder(0,n-7)}</g></svg>'
def bars(values, labels, w=520, hh=150):
    mx = max(values); n=len(values); bw = (w-40)/n
    s=''
    for i,(v,l) in enumerate(zip(values,labels)):
        bh = (hh-30)*v/mx
        s += f'<rect x="{20+i*bw+bw*.2:.1f}" y="{hh-20-bh:.1f}" width="{bw*.6:.1f}" height="{bh:.1f}" rx="3" fill="var(--primary)"/><text x="{20+i*bw+bw*.5:.1f}" y="{hh-6}" text-anchor="middle" font-size="11" fill="var(--t3)">{e(l)}</text><text x="{20+i*bw+bw*.5:.1f}" y="{hh-24-bh:.1f}" text-anchor="middle" font-size="11" fill="var(--t2)">{v}</text>'
    return f'<svg width="{w}" height="{hh}" viewBox="0 0 {w} {hh}">{s}</svg>'
def month_grid(marks):
    days=['일','월','화','수','목','금','토']
    s='<div class="mg">'+''.join(f'<div class="mh">{d}</div>' for d in days)
    start=6  # 11월 1일은 토요일(가정 시안)
    for i in range(35):
        d=i-start+1
        if d<1 or d>30: s+='<div class="md off"></div>'; continue
        ev=''.join(f'<div class="mev {k}">{e(t)}</div>' for k,t in marks.get(d,[]))
        s+=f'<div class="md"><b class="tnum">{d}</b>{ev}</div>'
    return s+'</div>'

NAV_ALL = [('home','대시보드'),('cal','촬영 일정'),('film','작품'),('users','신청자·선발'),('log','인원표·현장'),('msg','메시지·공지'),('star','인재 DB'),('box','자료 제공'),('shield','직원·권한'),('cog','설정'),('file','감사 로그'),('trash','휴지통')]
def desk(title, active, actions='', body='', who='관리자', role='모든 권한', nav=None, w=960, minh=480):
    nv=''.join(f'<div class="nav{" on" if lab==active else ""}">{ic(i,18)}{e(lab)}</div>' for i,lab in (nav or NAV_ALL))
    return (f'<div class="frame" style="width:{w}px"><div class="shell" style="min-height:{minh}px"><aside class="side"><div class="brand">OIO 캐스팅</div>{nv}'
            f'<div class="who"><b>{e(who)}</b><div class="cap">{e(role)}</div></div></aside><div class="main">{page_head(title,actions)}{body}</div></div></div>')
def bare(body, w=960, minh=480, bg=True):
    return f'<div class="frame" style="width:{w}px"><div class="bare" style="min-height:{minh}px">{body}</div></div>'
def modal_over(inner, w=960, minh=480):
    return f'<div class="frame" style="width:{w}px"><div class="scrim" style="min-height:{minh}px"><div class="modal">{inner}</div></div></div>'
def phone(bar, body, cta=None, tabs_=None, right='', back=False, h=700):
    tl = ''
    if tabs_:
        tl = '<div class="ptab">'+''.join(f'<div class="{"on" if i==tabs_[1] else ""}">{ic(ic_,20)}{e(t)}</div>' for i,(ic_,t) in enumerate(tabs_[0]))+'</div>'
    c = f'<div class="cta">{cta}</div>' if cta else ''
    bk = ic('back',22) if back else ''
    return (f'<div class="phone" style="height:{h}px"><div class="pbar"><span class="row" style="gap:8px">{bk}{e(bar)}</span>{right}</div>'
            f'<div class="pbody">{body}</div>{c}{tl}</div>')
def prow(title, sub='', right='', icon=None):
    i = f'{ic(icon,20,"var(--primary-text)")}' if icon else ''
    return f'<div class="prow">{i}<div class="m"><b>{e(title)}</b><span>{e(sub)}</span></div>{right}</div>'
def lrow(title, sub='', right=''):
    return prow(title, sub, right or ic('chev',20,'var(--t3)'))

CSS = r"""
:root{--bg:#F4F6F8;--surface:#FFFFFF;--t1:#191F28;--t2:#4E5968;--t3:#5B6573;--line:#E5E8EB;--primary:#0F766E;--on-primary:#FFFFFF;--primary-weak:#E6F4F1;--primary-text:#0B5F58;--ok-t:#067647;--ok-b:#E8F6EE;--warn-t:#B54708;--warn-b:#FFF4E5;--bad-t:#B42318;--bad-b:#FDECEA}
.scr *{box-sizing:border-box;margin:0}
.scr{font-family:"Pretendard",sans-serif;color:var(--t1);font-size:14px;line-height:20px}
.scr svg.i{stroke:currentColor;fill:none;stroke-width:1.75;stroke-linecap:round;stroke-linejoin:round;flex:none}
.scr .cap{font-size:12px;line-height:16px;color:var(--t3)}
.scr .tnum{font-variant-numeric:tabular-nums}
.scr .frame{border:1px solid var(--line);border-radius:12px;overflow:hidden;background:var(--bg)}
.scr .shell{display:grid;grid-template-columns:184px 1fr}
.scr .side{background:var(--surface);border-right:1px solid var(--line);padding:14px 10px;display:flex;flex-direction:column;gap:2px}
.scr .brand{font:700 15px/22px "Pretendard";padding:2px 10px 12px}
.scr .nav{display:flex;align-items:center;gap:9px;height:36px;padding:0 10px;border-radius:8px;color:var(--t2);font:500 13px/18px "Pretendard"}
.scr .nav.on{background:var(--primary-weak);color:var(--primary-text)}
.scr .who{margin-top:auto;padding:10px;border-top:1px solid var(--line);font-size:12px;line-height:16px}
.scr .main{padding:20px 24px;min-width:0}
.scr .bare{padding:36px;display:flex;justify-content:center;align-items:center;background:var(--bg)}
.scr .head{display:flex;align-items:center;justify-content:space-between;margin-bottom:14px;gap:12px}
.scr .row{display:flex;align-items:center;gap:10px}
.scr .card{background:var(--surface);border:1px solid var(--line);border-radius:12px;padding:16px}
.scr .stat{padding:12px 16px}.scr .stat .n{font:700 24px/30px "Pretendard"}.scr .stat .l{font-size:12px;color:var(--t2)}
.scr .grid{display:grid;gap:12px}.scr .g2{grid-template-columns:repeat(2,1fr)}.scr .g3{grid-template-columns:repeat(3,1fr)}.scr .g4{grid-template-columns:repeat(4,1fr)}
.scr .btn{display:inline-flex;align-items:center;justify-content:center;gap:6px;height:34px;padding:0 14px;border-radius:10px;border:1px solid transparent;font:500 13px/18px "Pretendard";background:var(--primary);color:var(--on-primary);white-space:nowrap}
.scr .btn.line{background:var(--surface);color:var(--t1);border-color:var(--line)}
.scr .btn.text{background:transparent;color:var(--primary-text);padding:0 6px}
.scr .btn.danger{background:var(--bad-b);color:var(--bad-t)}
.scr .btn.off{background:var(--line);color:var(--t3)}
.scr .btn.lg{height:48px;font-size:15px;width:100%}
.scr .btn.sm{height:28px;font-size:12px;padding:0 10px}
.scr .badge{display:inline-flex;align-items:center;height:22px;padding:0 8px;border-radius:6px;font:500 12px/16px "Pretendard";white-space:nowrap}
.scr .b-ok{background:var(--ok-b);color:var(--ok-t)}.scr .b-warn{background:var(--warn-b);color:var(--warn-t)}.scr .b-bad{background:var(--bad-b);color:var(--bad-t)}.scr .b-pri{background:var(--primary-weak);color:var(--primary-text)}.scr .b-gray{background:var(--bg);color:var(--t2);border:1px solid var(--line)}
.scr .field{display:flex;flex-direction:column;gap:5px}.scr .field label{font:500 12px/16px "Pretendard";color:var(--t2)}
.scr .input{min-height:36px;border:1px solid var(--line);border-radius:10px;padding:0 12px;background:var(--surface);display:flex;align-items:center;font-size:13px;color:var(--t1)}
.scr .input.ph{color:var(--t3)}.scr .input.sel{justify-content:space-between}.scr .input.focus{border:2px solid var(--primary);padding:0 11px}.scr .input.err{border-color:var(--bad-t)}.scr .input.ta{align-items:flex-start;padding:9px 12px;line-height:19px}
.scr .errmsg{font-size:12px;line-height:16px;color:var(--bad-t)}
.scr .alert{padding:10px 14px;border-radius:10px;font-size:13px;line-height:19px}.scr .a-warn{background:var(--warn-b);color:var(--warn-t)}.scr .a-ok{background:var(--ok-b);color:var(--ok-t)}.scr .a-bad{background:var(--bad-b);color:var(--bad-t)}.scr .a-info{background:var(--primary-weak);color:var(--primary-text)}
.scr .tabs{display:inline-flex;border:1px solid var(--line);border-radius:10px;overflow:hidden;background:var(--surface)}
.scr .tabs span{padding:0 12px;height:32px;display:flex;align-items:center;font:500 12px "Pretendard";color:var(--t2)}.scr .tabs span.on{background:var(--primary-weak);color:var(--primary-text)}
.scr .filters{display:flex;gap:8px;margin-bottom:12px;flex-wrap:wrap}
.scr .chip{height:28px;gap:3px;padding:0 11px;border:1px solid var(--line);border-radius:14px;background:var(--surface);display:flex;align-items:center;font:500 12px "Pretendard";color:var(--t2)}.scr .chip.on{background:var(--primary-weak);color:var(--primary-text);border-color:transparent}
.scr table{width:100%;border-collapse:collapse;background:var(--surface);border:1px solid var(--line);font-size:13px}
.scr th{text-align:left;font:500 12px/16px "Pretendard";color:var(--t2);padding:9px 12px;border-bottom:1px solid var(--line)}
.scr td{padding:0 12px;height:42px;border-bottom:1px solid var(--line);vertical-align:middle}.scr tr:last-child td{border-bottom:0}
.scr td.r,.scr th.r{text-align:right}.scr td.c,.scr th.c{text-align:center}
.scr .day{font:700 13px/18px "Pretendard";padding:12px 0 6px;color:var(--t2)}
.scr .it{display:grid;grid-template-columns:104px 1fr 70px 76px 70px;align-items:center;gap:10px;min-height:50px;padding:6px 14px;background:var(--surface);border:1px solid var(--line);border-top:0}
.scr .it.first{border-top:1px solid var(--line);border-radius:10px 10px 0 0}.scr .it.last{border-radius:0 0 10px 10px}.scr .it.first.last{border-radius:10px}
.scr .it .t{font-weight:700}.scr .it .pl{color:var(--t2);font-size:12px;line-height:16px}
.scr .radio{display:flex;align-items:center;gap:9px;min-height:34px;font-size:13px}
.scr .radio i{width:16px;height:16px;border:1.5px solid var(--t3);border-radius:50%;display:block;flex:none}.scr .radio i.on{border:5px solid var(--primary)}
.scr .checks{display:flex;flex-wrap:wrap;gap:8px 18px}.scr .ck{display:flex;align-items:center;gap:7px;font-size:13px}
.scr .ck i{width:16px;height:16px;border:1.5px solid var(--t3);border-radius:4px;display:flex;align-items:center;justify-content:center;flex:none;color:var(--on-primary)}
.scr .ck i.on{background:var(--primary);border-color:var(--primary)}.scr .ck i svg{width:12px;height:12px;stroke-width:2.6}.scr .ck.warn{color:var(--warn-t)}
.scr .sw2{width:42px;height:24px;border-radius:12px;background:var(--line);position:relative;display:inline-block;flex:none}.scr .sw2 i{position:absolute;left:3px;top:3px;width:18px;height:18px;border-radius:50%;background:var(--surface);border:1px solid var(--line)}
.scr .sw2.on{background:var(--primary)}.scr .sw2.on i{left:auto;right:3px;background:var(--on-primary);border-color:transparent}
.scr .scrim{background:rgba(17,20,24,.45);display:flex;justify-content:center;align-items:center;padding:28px}
.scr .modal{width:420px;background:var(--surface);border-radius:12px;padding:22px;box-shadow:0 8px 24px rgba(0,0,0,.12)}
.scr .modal h3,.scr h3{font:700 17px/24px "Pretendard"}
.scr .kv{display:grid;grid-template-columns:110px 1fr;gap:8px 12px;font-size:13px}.scr .kv .k{color:var(--t2)}
.scr .av{display:inline-flex;align-items:center;justify-content:center;border-radius:50%;background:var(--primary-weak);color:var(--primary-text);font-weight:700;flex:none}
.scr .photo{background:#E9EDF0;border-radius:10px;display:flex;flex-direction:column;align-items:center;justify-content:center;color:#7B8794;gap:4px;font-size:11px;position:relative;flex:none}
.scr .vid{background:#2B3138;color:#fff}.scr .vid .pl{width:40px;height:40px;border-radius:50%;background:rgba(255,255,255,.18);display:flex;align-items:center;justify-content:center}
.scr .vid .dur{position:absolute;right:6px;bottom:5px;font-size:11px}.scr .vid .lb{position:absolute;left:8px;bottom:5px;font-size:11px}
.scr .mg{display:grid;grid-template-columns:repeat(7,1fr);border:1px solid var(--line);background:var(--surface);border-radius:10px;overflow:hidden}
.scr .mh{padding:6px 8px;font:500 11px "Pretendard";color:var(--t2);border-bottom:1px solid var(--line);background:var(--bg)}
.scr .md{min-height:62px;padding:4px 6px;border-right:1px solid var(--line);border-bottom:1px solid var(--line);font-size:11px;line-height:14px}.scr .md.off{background:var(--bg)}.scr .md b{font-weight:500;color:var(--t2)}
.scr .mev{margin-top:2px;padding:1px 5px;border-radius:4px;font-size:10.5px;line-height:14px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.scr .mev.a{background:var(--primary-weak);color:var(--primary-text)}.scr .mev.b{background:var(--warn-b);color:var(--warn-t)}.scr .mev.c{background:var(--bg);color:var(--t2);border:1px solid var(--line)}
.scr .phone{position:relative;width:360px;border:1px solid var(--line);border-radius:26px;overflow:hidden;background:var(--bg);display:flex;flex-direction:column}
.scr .pbar{height:46px;flex:none;display:flex;align-items:center;justify-content:space-between;padding:0 18px;font:700 17px/24px "Pretendard";background:var(--surface);border-bottom:1px solid var(--line)}
.scr .pbody{padding:16px;display:flex;flex-direction:column;gap:10px;flex:1;min-height:0;overflow:hidden}
.scr .prow{display:flex;align-items:center;gap:12px;min-height:52px;padding:10px 14px;background:var(--surface);border:1px solid var(--line);border-radius:12px}
.scr .prow .m{flex:1;min-width:0}.scr .prow .m b{display:block;font-weight:700;font-size:14px;line-height:20px}.scr .prow .m span{display:block;font-size:12px;line-height:16px;color:var(--t2)}
.scr .ptab{height:56px;flex:none;border-top:1px solid var(--line);background:var(--surface);display:grid;grid-template-columns:repeat(4,1fr)}
.scr .ptab div{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:1px;font:500 11px/14px "Pretendard";color:var(--t3)}.scr .ptab div.on{color:var(--primary-text)}
.scr .cta{padding:10px 16px 16px;background:var(--bg);flex:none}
.scr .sp{flex:1}
"""
