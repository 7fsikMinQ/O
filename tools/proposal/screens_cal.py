# -*- coding: utf-8 -*-
"""직원 일정 확인 화면(월·주·일, 앱과 웹). 관리자·스케줄러가 등록한 일정이 직원에게 보이는 모습."""
from lib import *
from screens_web1 import add

TABS = ([('cal','오늘'),('search','일정'),('qr','출결'),('user','내 정보')],1)

def seg(items, on):
    return '<div class="seg">'+''.join(f'<span class="{"on" if i==on else ""}">{e(t)}</span>' for i,t in enumerate(items))+'</div>'
def pchips(items, on=0):
    return '<div class="row" style="gap:6px;flex-wrap:wrap">'+''.join(f'<span class="chip{" on" if i==on else ""}">{e(t)}</span>' for i,t in enumerate(items))+'</div>'

def pmonth(marks, today=19, sel=19):
    heads=''.join(f'<div class="pmh{" sun" if i==0 else ""}">{d}</div>' for i,d in enumerate('일월화수목금토'))
    cells=''
    start=6  # 11월 1일 = 토요일(시안)
    for i in range(42):
        d=i-start+1
        if i>=35 and d>30: break
        if d<1 or d>30: cells+='<div class="pmd off"></div>'; continue
        cls='pmd'+(' today' if d==today else '')+(' sel' if d==sel else '')+(' sun' if i%7==0 else '')
        dots=''.join(f'<i class="dt {k}"></i>' for k in marks.get(d,[])[:3])
        cnt = len(marks.get(d,[]))
        extra = f'<em>+{cnt-3}</em>' if cnt>3 else ''
        cells+=f'<div class="{cls}"><b class="tnum">{d}</b><span class="dots">{dots}{extra}</span></div>'
    return f'<div class="pmonth"><div class="pmg">{heads}{cells}</div></div>'

def evrow(time, title, place, badge_html, changed=False, mine=False):
    tag = '<span class="chg">변경됨</span>' if changed else ''
    mn = '<span class="mine">내 담당</span>' if mine else ''
    return (f'<div class="evr"><div class="evt tnum">{time}</div><div class="evb"><div class="evh"><b>{e(title)}</b>{tag}</div>'
            f'<div class="evp">{e(place)}</div><div class="evf">{badge_html}{mn}</div></div></div>')

# ---------- 앱 ----------
add('SM08','월간 일정 (앱)','2','phone','직원 전체','한 달 일정을 달력으로 훑고, 날짜를 누르면 그날 일정이 아래에 나타납니다.',
 ['날짜 아래 점: 일정 수(최대 3개 점, 넘으면 +N), 주황 점은 팀장이 필요한 일정','오늘은 채운 원, 선택한 날은 테두리로 표시','"내 일정만 / 팀장 필요" 칩으로 바로 걸러 보기, 위로 올리면 달력이 줄고 목록이 넓어짐'],'SCHEDULE_READ',
 phone('일정','' ,None,TABS,right=ic('search',20)).replace('<div class="pbody"></div>',
  '<div class="pbody" style="gap:6px;padding:10px 14px">'
  +seg(['월','주','일'],0)
  +'<div class="row" style="justify-content:space-between"><b style="font-size:17px">2026년 11월</b><span class="row" style="gap:14px;color:var(--t2)">'+ic('back',20)+ic('chev',20)+'</span></div>'
  +pchips(['전체','내 일정','팀장 필요'],0)
  +pmonth({17:['a'],18:['a','a'],19:['a','b'],20:['a','a','a'],21:['c'],22:['a'],24:['a'],25:['a','a'],26:['b'],27:['b'],28:['a','a','b','a'],29:['c']},19,19)
  +'<div class="legend"><span><i class="dt a"></i>일정</span><span><i class="dt b"></i>팀장 필요</span><span><i class="dt c"></i>초안</span></div>'
  +'<div class="cap" style="margin-top:2px">11월 19일 수요일 · 2건</div>'
  +evrow('09:00','사극 가제 3회차','강남 세트장',badge('확정','ok'),mine=True)
  +evrow('13:00','현대극 카페 장면','성수동 스튜디오',badge('팀장 필요','warn'))
  +'</div>'))
add('SM09','주간 일정 (앱)','2','phone','직원 전체','이번 주 일정을 요일별로 묶어 봅니다. 위의 요일 띠에서 날짜를 바로 고를 수 있습니다.',
 ['요일 띠: 일정 있는 날은 점, 오늘은 강조','요일별 묶음 안에 시간순으로 정렬, 변경된 일정에 "변경됨" 표시','팀장이 부족한 일정은 주황 라벨로 눈에 띄게'],'SCHEDULE_READ',
 phone('일정','',None,TABS,right=ic('search',20)).replace('<div class="pbody"></div>',
  '<div class="pbody" style="gap:8px;padding:12px 14px">'
  +seg(['월','주','일'],1)
  +'<div class="row" style="justify-content:space-between"><b style="font-size:17px">11월 3주차</b><span class="cap">16일 – 22일</span></div>'
  +'<div class="wk">'+''.join(f'<div class="wd{" today" if d==19 else ""}{" has" if d in (17,18,19,20,22) else ""}"><span>{w}</span><b class="tnum">{d}</b><i></i></div>' for w,d in zip('일월화수목금토',[16,17,18,19,20,21,22]))+'</div>'
  +pchips(['전체','내 일정','팀장 필요'],0)
  +'<div class="dh">수 19일 · 오늘</div>'
  +evrow('09:00','사극 가제 3회차','강남 세트장',badge('확정','ok'),changed=True,mine=True)
  +evrow('13:00','현대극 카페 장면','성수동 스튜디오',badge('팀장 필요','warn'))
  +'<div class="dh">목 20일</div>'
  +evrow('06:00','사극 가제 4회차 (야외)','양평 오픈 세트',badge('모집 중','pri'))
  +'</div>'))
add('SM10','일간 일정 (앱, 시간표)','2','phone','직원 전체','하루를 시간 축으로 봅니다. 겹치는 일정은 나란히, 지금 시각은 선으로 표시합니다.',
 ['06시~22시 시간 축, 일정 길이만큼 막대 표시','겹치는 일정은 좌우로 나눠 표시, 지금 시각 선','막대를 누르면 일정 상세(팀장 등록·전화·지도)'],'SCHEDULE_READ',
 phone('일정','',None,TABS,right=ic('search',20)).replace('<div class="pbody"></div>',
  '<div class="pbody" style="gap:8px;padding:12px 14px">'
  +seg(['월','주','일'],2)
  +'<div class="row" style="justify-content:space-between"><b style="font-size:17px">11월 19일 수요일</b><span class="cap">오늘 · 2건</span></div>'
  +'<div class="tl">'
  +''.join(f'<div class="tr" style="top:{(h-6)*30}px"><span class="tnum">{h:02d}:00</span></div>' for h in range(6,22,2))
  +'<div class="te a" style="top:90px;height:268px;left:46px;right:6px"><b>사극 가제 3회차</b><span class="tnum">09:00–18:00 · 강남 세트장</span><em>확정 · 팀장 1/2</em></div>'
  +'<div class="te b" style="top:210px;height:208px;left:46px;right:6px;margin-left:50%;width:auto"><b>현대극 카페</b><span class="tnum">13:00–20:00<br>성수동</span><em>팀장 필요</em></div>'
  +'<div class="now" style="top:135px"><i></i><span class="tnum">10:30</span></div>'
  +'</div></div>'))
add('SM11','일정 변경 알림 상세 (앱)','2','phone','직원 전체','관리자·스케줄러가 일정을 등록·수정하면 알림이 오고, 무엇이 어떻게 바뀌었는지 이전 값과 비교해 보여 줍니다.',
 ['"집합 시간 08:30 → 09:00"처럼 이전 값과 바뀐 값을 나란히 표시','누가 언제 바꿨는지, 사유(선택지+기타)를 함께 표시','알림을 읽으면 월간·주간 화면의 "변경됨" 표시가 사라짐'],'SCHEDULE_READ',
 phone('일정 변경','',None,None,back=True).replace('<div class="pbody"></div>',
  '<div class="pbody">'
  +alert('사극 가제 3회차 일정이 바뀌었어요.','warn')
  +card('<div style="display:grid;gap:10px"><div class="cmp"><span class="k">집합 시간</span><span class="o tnum">08:30</span><span class="ar">→</span><b class="n tnum">09:00</b></div><div class="cmp"><span class="k">종료 예상</span><span class="o tnum">17:00</span><span class="ar">→</span><b class="n tnum">18:00</b></div><div class="cmp"><span class="k">장소</span><span class="o">강남 세트장</span><span class="ar">=</span><span>변경 없음</span></div></div>',14)
  +kv([('변경한 사람','박스케줄 (스케줄러)'),('변경 시각','11월 18일 14:20'),('사유','촬영 일정 변경')])
  +prow('일정 상세 보기','사극 가제 3회차','')
  +'</div>'))
def _sm12():
    base = phone('일정','',None,TABS).replace('<div class="pbody"></div>','<div class="pbody"><div class="cap">11월 19일 수요일 · 2건</div>'+evrow('09:00','사극 가제 3회차','강남 세트장',badge('확정','ok'))+evrow('13:00','현대극 카페 장면','성수동 스튜디오',badge('팀장 필요','warn'))+'</div>')
    sheet = ('<div style="position:absolute;inset:0;background:rgba(17,20,24,.40)"></div>'
      '<div style="position:absolute;left:0;right:0;bottom:0;background:var(--surface);border-radius:20px 20px 0 0;padding:16px;box-shadow:0 -8px 24px rgba(0,0,0,.12);display:grid;gap:10px">'
      '<b style="font-size:17px">필터</b>'+prow('내 일정만','내가 팀장으로 등록한 일정',switch(True))+prow('팀장 필요만','팀장이 부족한 일정',switch(False))+prow('변경된 일정만','최근 3일 안에 바뀐 일정',switch(False))
      +'<div class="field"><label>작품</label>'+checks([("사극 가제",True),("현대극 카페",True),("광고 촬영",False)])+'</div>'
      +'<div class="row" style="gap:8px">'+btn('초기화','line',None,'flex:1')+btn('적용','',None,'flex:2')+'</div></div>')
    return base[:base.rfind('</div>')] + sheet + '</div>'
add('SM12','일정 필터 시트 (앱)','2','phone','직원 전체','작품·상태·담당·기간으로 일정을 걸러 봅니다. 아래에서 올라오는 시트입니다.',
 ['내 일정만, 팀장 필요만, 변경된 일정만 켜고 끄기','작품·상태는 여러 개 선택 가능','"적용"을 누르면 월·주·일 화면에 같은 조건이 유지됨'],'SCHEDULE_READ', _sm12())

# ---------- 웹 ----------
def hl():
    return ''.join(f'<div class="hl" style="top:{i*22}px"></div>' for i in range(1,16))
def wk_events():
    cols=''
    days=['일 16','월 17','화 18','수 19','목 20','금 21','토 22']
    ev={1:[('a',6,18,'사극 2회차')],2:[('a',8,17,'사극 2회차'),('c',18,21,'광고 미팅')],3:[('a',9,18,'사극 3회차'),('b',13,20,'카페 장면')],4:[('a',6,22,'사극 4회차(야외)'),('c',10,14,'광고(초안)')],6:[('a',9,18,'사극 5회차')]}
    for i,dn in enumerate(days):
        blocks=''
        lst=ev.get(i,[])
        for j,(k,s,e_,t) in enumerate(lst):
            ov = [x for x in lst if x is not lst[j] and x[1] < e_ and s < x[2]]
            if ov:
                w = 50; left = 0 if j==0 or all(lst.index(x)>j for x in ov) else 50
            else:
                w = 100; left = 0
            blocks+=f'<div class="wev {k}" style="top:{(s-6)*22}px;height:{(e_-s)*22-2}px;left:{left}%;width:{w-2}%"><b>{t}</b><span class="tnum">{s:02d}:00–{e_:02d}:00</span></div>'
        cols+=f'<div class="wcol{" today" if i==3 else ""}"><div class="wch">{dn}</div><div class="wcb" style="height:{16*22}px">{hl()}{blocks}</div></div>'
    hrs=''.join(f'<div class="whr" style="height:22px">{h:02d}</div>' for h in range(6,22))
    return f'<div class="wgrid"><div class="wtime"><div class="wch">&nbsp;</div>{hrs}</div>{cols}</div>'
add('SW33','주간 일정 (웹)','1','web','일정 열람 권한 이상','일주일 일정을 시간표로 봅니다. 관리자·스케줄러가 등록하면 즉시 이 화면에 나타납니다.',
 ['요일 칸에 시간 길이만큼 막대 표시, 겹치면 나란히','막대 색: 확정, 팀장 필요, 초안을 글자 라벨과 함께 구분','막대를 누르면 일정 상세, 빈 곳을 누르면 새 일정 등록(권한자)'],'SCHEDULE_READ',
 desk('촬영 일정','촬영 일정',tabs(['월','주','일','목록'],1)+btn('일정 등록','','plus'),
  '<div class="row" style="margin-bottom:10px;gap:12px"><b style="font-size:16px">11월 3주차 (16일–22일)</b><span class="cap">이전 · 오늘 · 다음</span></div>'+wk_events(),minh=560))
def day_lanes():
    lanes=[('강남 세트장',[('a',9,18,'사극 가제 3회차','팀장 1/2 · 필요 30')]),('성수동 스튜디오',[('b',13,20,'현대극 카페 장면','팀장 0/1 · 필요 8')]),('서울숲',[('c',10,14,'광고 촬영(초안)','팀장 1/1 · 필요 6')])]
    hrs=''.join(f'<div class="whr" style="height:22px">{h:02d}</div>' for h in range(6,22))
    cols=''
    for name,evs in lanes:
        b=''.join(f'<div class="wev {k}" style="top:{(s-6)*22}px;height:{(e_-s)*22-2}px;left:2%;width:96%"><b>{t}</b><span class="tnum">{s:02d}:00–{e_:02d}:00</span><span>{m}</span></div>' for k,s,e_,t,m in evs)
        cols+=f'<div class="wcol"><div class="wch">{name}</div><div class="wcb" style="height:{16*22}px">{hl()}{b}<div class="nowl" style="top:{(10.5-6)*22}px"></div></div></div>'
    return f'<div class="wgrid"><div class="wtime"><div class="wch">&nbsp;</div>{hrs}</div>{cols}</div>'
add('SW34','일간 일정 (웹, 현장별 시간표)','1','web','일정 열람 권한 이상','하루 일정을 촬영 장소별 줄로 나눠 봅니다. 같은 시간에 어디서 무엇이 진행되는지 한눈에 확인합니다.',
 ['장소별 줄 + 시간 축, 지금 시각 선','줄 위에 팀장 현황과 필요 인원을 함께 표시','팀장이 없는 줄은 주황 라벨로 표시'],'SCHEDULE_READ',
 desk('촬영 일정','촬영 일정',tabs(['월','주','일','목록'],2)+btn('일정 등록','','plus'),
  '<div class="row" style="margin-bottom:10px;gap:12px"><b style="font-size:16px">11월 19일 수요일</b><span class="cap">오늘 · 일정 3건</span></div>'+day_lanes(),minh=540))
