(function(){
'use strict';

/* ---------- Stałe i narzędzia ---------- */
var KEY='asystent_lekow_v3', APP_VERSION='0.2.0-preview';
var DOW=['Nd','Pn','Wt','Śr','Cz','Pt','So'];
var DOW_FULL=['niedziela','poniedziałek','wtorek','środa','czwartek','piątek','sobota'];
var DOW_ORDER=[1,2,3,4,5,6,0];
var MONTHS=['stycznia','lutego','marca','kwietnia','maja','czerwca','lipca','sierpnia','września','października','listopada','grudnia'];
var ALL_DOW=[0,1,2,3,4,5,6];

function $(id){return document.getElementById(id);}
function pad(n){return String(n).padStart(2,'0');}
function isoLocal(d){return d.getFullYear()+'-'+pad(d.getMonth()+1)+'-'+pad(d.getDate());}
function parseIso(s){var p=s.split('-').map(Number);return new Date(p[0],p[1]-1,p[2]);}
function todayIso(){return isoLocal(new Date());}
function hhmm(d){return pad(d.getHours())+':'+pad(d.getMinutes());}
function addDays(iso,n){var d=parseIso(iso);d.setDate(d.getDate()+n);return isoLocal(d);}
function daysBetween(a,b){return Math.round((parseIso(b)-parseIso(a))/864e5);}
function isIso(s){return typeof s==='string'&&/^\d{4}-\d\d-\d\d$/.test(s);}
function isTime(s){return typeof s==='string'&&/^\d{2}:\d{2}$/.test(s);}
function plDate(iso){var p=iso.split('-');return p[2]+'.'+p[1]+'.'+p[0];}
function shortDate(iso){var p=iso.split('-');return p[2]+'.'+p[1];}
function esc(s){return String(s==null?'':s).replace(/[&<>"]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c];});}
function cap(s){return s.charAt(0).toUpperCase()+s.slice(1);}
function uid(){return Date.now().toString(36)+Math.random().toString(36).slice(2,6);}
function num(n){return String(Math.round(n*100)/100).replace('.',',');}
function longDay(iso){var d=parseIso(iso);return cap(DOW_FULL[d.getDay()])+' '+d.getDate()+' '+MONTHS[d.getMonth()];}
function numOr(v,def,min,max){var n=parseFloat(String(v).replace(',','.'));if(isNaN(n))return def;return Math.min(max,Math.max(min,n));}
function objOf(o){return (o&&typeof o==='object'&&!Array.isArray(o))?o:{};}
function slotIcon(t){var h=parseInt(t.slice(0,2),10);return h>=5&&h<11?'ph-sun-horizon':(h>=11&&h<17?'ph-sun':(h>=17&&h<21?'ph-cloud-moon':'ph-moon-stars'));}
function sameArr(a,b){return a.length===b.length&&a.every(function(x,i){return x===b[i];});}
function sameDoses(a,b){var ka=Object.keys(a),kb=Object.keys(b);return ka.length===kb.length&&ka.every(function(k){return a[k]===b[k];});}

/* ---------- Dane domyślne ---------- */
function defaultSlots(){return [
  {id:'morning',name:'Śniadanie',time:'08:00'},
  {id:'lunch',name:'Obiad',time:'14:30'},
  {id:'dinner',name:'Kolacja',time:'19:00'},
  {id:'night',name:'Przed snem',time:'22:00'}
];}
function defaultRp(){return {bp:true,labs:true,labsPeriod:false,meds:true,plan:true,events:true};}
function defaultAi(){return {meds:true,history:true,adherence:true,bp:true,labs:true,events:true,notes:true,name:false,context:''};}
function defaultSettings(){return {rp:defaultRp(),ai:defaultAi(),lateMin:60,waterGoal:10,glassMl:250,hiSys:135,hiDia:85,loSys:100,loDia:60,theme:'auto',reportNote:'',remind:{on:false,lead:0}};}

function defaultMarkers(){return [
  {id:'potas',name:'Potas',unit:'mmol/l',lo:3.5,hi:5.5,grp:'Elektrolity'},
  {id:'sod',name:'Sód',unit:'mmol/l',lo:136,hi:146,grp:'Elektrolity'},
  {id:'magnez',name:'Magnez',unit:'mmol/l',lo:0.7,hi:1.1,grp:'Elektrolity'},
  {id:'kreatynina',name:'Kreatynina',unit:'mg/dl',lo:0.67,hi:1.17,grp:'Nerki i metabolizm'},
  {id:'moczowy',name:'Kwas moczowy',unit:'mg/dl',lo:3.5,hi:7.2,grp:'Nerki i metabolizm'}
];}
var DEFAULT_GRP={potas:'Elektrolity',sod:'Elektrolity',magnez:'Elektrolity',kreatynina:'Nerki i metabolizm',moczowy:'Nerki i metabolizm',glukoza:'Nerki i metabolizm',
  leukocyty:'Morfologia',erytrocyty:'Morfologia',hemoglobina:'Morfologia',hematokryt:'Morfologia',plytki:'Morfologia',chol:'Lipidy',hdl:'Lipidy',ldl:'Lipidy',tg:'Lipidy',
  alt:'Wątroba i tarczyca',ast:'Wątroba i tarczyca',ggtp:'Wątroba i tarczyca',tsh:'Wątroba i tarczyca',ob:'Stan zapalny i inne',crp:'Stan zapalny i inne',zelazo:'Stan zapalny i inne',psa:'Stan zapalny i inne'};
function numN(v){if(v===null||v===undefined||String(v).trim()==='') return null; var n=parseFloat(String(v).replace(',','.')); return isFinite(n)?n:null;}
function blankDay(){return {taken:{},times:{},skipped:{},water:0,note:'',results:{}};}
function freshState(){
  return {v:3,patient:'',created:todayIso(),lastBackup:null,settings:defaultSettings(),rules:'',slots:defaultSlots(),meds:[],events:[],markers:defaultMarkers(),labs:[],days:{},bp:[],timerEnd:0,timerMed:'',timerNote:''};
}

/* ---------- Normalizacja ---------- */
function normalize(d){
  var s=freshState();
  if(!d||typeof d!=='object') return s;
  if(typeof d.patient==='string') s.patient=d.patient.trim();
  if(isIso(d.created)) s.created=d.created;
  if(isIso(d.lastBackup)) s.lastBackup=d.lastBackup;
  var st=objOf(d.settings), D=s.settings;
  var rpIn=objOf(st.rp), rpOut=defaultRp(), rmIn=objOf(st.remind), aiIn=objOf(st.ai), aiOut=defaultAi();
  Object.keys(rpOut).forEach(function(k){if(typeof rpIn[k]==='boolean') rpOut[k]=rpIn[k];});
  Object.keys(aiOut).forEach(function(k){if(typeof aiOut[k]==='boolean'){if(typeof aiIn[k]==='boolean') aiOut[k]=aiIn[k];}});
  if(typeof aiIn.context==='string') aiOut.context=aiIn.context.slice(0,2000);
  s.settings={
    rp:rpOut, ai:aiOut, lateMin:Math.round(numOr(st.lateMin,D.lateMin,0,600)), waterGoal:Math.round(numOr(st.waterGoal,D.waterGoal,0,20)), glassMl:Math.round(numOr(st.glassMl,D.glassMl,50,1000)),
    hiSys:Math.round(numOr(st.hiSys,D.hiSys,100,250)), hiDia:Math.round(numOr(st.hiDia,D.hiDia,50,150)),
    loSys:Math.round(numOr(st.loSys,D.loSys,60,130)), loDia:Math.round(numOr(st.loDia,D.loDia,30,100)),
    theme:['auto','light','dark'].indexOf(st.theme)>=0?st.theme:'auto',
    reportNote:typeof st.reportNote==='string'?st.reportNote:'',
    remind:{on:rmIn.on===true,lead:Math.round(numOr(rmIn.lead,0,0,120))}
  };
  s.rules=typeof d.rules==='string'?d.rules:'';
  var sl=[];
  (Array.isArray(d.slots)?d.slots:[]).forEach(function(x){
    if(x&&typeof x.id==='string'&&x.id&&typeof x.name==='string'&&isTime(x.time)) sl.push({id:x.id,name:x.name,time:x.time});
  });
  if(sl.length) s.slots=sl;
  (Array.isArray(d.meds)?d.meds:[]).forEach(function(x){
    if(!x||typeof x.id!=='string'||typeof x.name!=='string'||!Array.isArray(x.phases)) return;
    var ph=[];
    x.phases.forEach(function(p){
      if(!p||!isIso(p.from)||!p.doses||typeof p.doses!=='object') return;
      var doses={};
      Object.keys(p.doses).forEach(function(k){var v=+p.doses[k]; if(v>0&&v<=50) doses[k]=v;});
      var dow=(Array.isArray(p.dow)?p.dow:ALL_DOW).filter(function(n){return n>=0&&n<=6&&n%1===0;});
      if(!dow.length) dow=ALL_DOW.slice();
      ph.push({from:p.from,doses:doses,dow:dow});
    });
    if(!ph.length) return;
    ph.sort(function(a,b){return a.from<b.from?-1:(a.from>b.from?1:0);});
    s.meds.push({id:x.id,name:x.name,substance:String(x.substance||''),note:String(x.note||''),unit:String(x.unit||'tabl.'),
      timerMin:Math.round(numOr(x.timerMin,0,0,600)),mmol:numOr(x.mmol,0,0,1000),end:isIso(x.end)?x.end:null,phases:ph});
  });
  (Array.isArray(d.events)?d.events:[]).forEach(function(e){
    if(e&&typeof e.id==='string'&&isIso(e.date)&&typeof e.title==='string') s.events.push({id:e.id,date:e.date,title:e.title,note:String(e.note||''),field:String(e.field||''),marker:typeof e.marker==='string'?e.marker:''});
  });
  var mk=[];
  (Array.isArray(d.markers)?d.markers:[]).forEach(function(x){
    if(x&&typeof x.id==='string'&&x.id&&typeof x.name==='string'&&x.name.trim()) mk.push({id:x.id,name:x.name.trim(),unit:String(x.unit||''),lo:numN(x.lo),hi:numN(x.hi),grp:String(x.grp||DEFAULT_GRP[x.id]||'').trim()});
  });
  s.markers=mk.length?mk:defaultMarkers();
  function hasMarker(id){return s.markers.some(function(m){return m.id===id;});}
  (Array.isArray(d.labs)?d.labs:[]).forEach(function(x){
    if(x&&typeof x.id==='string'&&isIso(x.date)&&typeof x.marker==='string'&&hasMarker(x.marker)&&typeof x.value==='number'&&isFinite(x.value)) s.labs.push({id:x.id,date:x.date,marker:x.marker,value:x.value,note:String(x.note||'')});
  });
  s.events.forEach(function(e){
    if(e.marker&&!hasMarker(e.marker)) e.marker='';
  });
  Object.keys(objOf(d.days)).forEach(function(k){
    if(!isIso(k)) return;
    var r=objOf(d.days[k]);
    var sk={}; Object.keys(objOf(r.skipped)).forEach(function(key){var x=objOf(r.skipped[key]); sk[key]={reason:String(x.reason||'Bez podania powodu'),note:String(x.note||'')};});
    s.days[k]={taken:objOf(r.taken),times:objOf(r.times),skipped:sk,water:Math.round(numOr(r.water,0,0,100)),note:typeof r.note==='string'?r.note:'',results:objOf(r.results)};
  });
  (Array.isArray(d.bp)?d.bp:[]).forEach(function(r){
    if(r&&typeof r.sys==='number'&&typeof r.dia==='number'&&isIso(r.date)&&isTime(r.time)){
      s.bp.push({id:String(r.id||uid()),date:r.date,time:r.time,sys:r.sys,dia:r.dia,pulse:r.pulse||null,ctx:String(r.ctx||'inne'),note:String(r.note||'')});
    }
  });
  s.events.forEach(function(e){
    if(!e.marker) return;
    var r=((s.days[e.date]||{}).results||{})[e.id], n=numN(r), id='ev:'+e.id;
    if(n!==null&&!s.labs.some(function(l){return l.id===id;})) s.labs.push({id:id,date:e.date,marker:e.marker,value:n,note:e.title});
  });
  s.timerEnd=+d.timerEnd||0; s.timerMed=String(d.timerMed||''); s.timerNote=String(d.timerNote||'');
  return s;
}
function load(){
  try{var r=localStorage.getItem(KEY); if(r){var d=JSON.parse(r); if(d&&d.v===3) return normalize(d);}}catch(e){}
  return freshState();
}
var S=load();
function saveLocal(){
  try{localStorage.setItem(KEY,JSON.stringify(S));}
  catch(e){toast('Nie udało się zapisać danych na urządzeniu. Zrób kopię JSON.');}
}
function save(){saveLocal(); queueReminders();}
function dayRec(iso){if(!S.days[iso]) S.days[iso]=blankDay(); return S.days[iso];}

/* ---------- Model leków ---------- */
function sortedSlots(){return S.slots.slice().sort(function(a,b){return a.time<b.time?-1:(a.time>b.time?1:0);});}
function medById(id){for(var i=0;i<S.meds.length;i++) if(S.meds[i].id===id) return S.meds[i]; return null;}
function slotById(id){for(var i=0;i<S.slots.length;i++) if(S.slots[i].id===id) return S.slots[i]; return null;}
function phaseAt(m,iso){var p=null; m.phases.forEach(function(x){if(x.from<=iso) p=x;}); return p;}
function medActive(m,iso){return m.phases.length&&iso>=m.phases[0].from&&(!m.end||iso<=m.end);}
function scheduleFor(iso){
  var wd=parseIso(iso).getDay(), out=[];
  sortedSlots().forEach(function(sl){
    S.meds.forEach(function(m){
      if(!medActive(m,iso)) return;
      var p=phaseAt(m,iso); if(!p||p.dow.indexOf(wd)<0) return;
      var d=p.doses[sl.id];
      if(d>0) out.push({med:m,slot:sl,dose:d,key:m.id+'|'+sl.id});
    });
  });
  return out;
}
function dayStats(iso){
  var items=scheduleFor(iso), r=S.days[iso]||blankDay(), n=0, mt=0, mp=0, hasM=false, sk=0;
  items.forEach(function(x){
    var on=!!r.taken[x.key];
    if(on) n++; else if((r.skipped||{})[x.key]) sk++;
    if(x.med.mmol>0){hasM=true; mp+=x.dose*x.med.mmol; if(on) mt+=x.dose*x.med.mmol;}
  });
  return {items:items,tot:items.length,n:n,sk:sk,mmolTaken:mt,mmolPlan:mp,hasMmol:hasM};
}
function firstDate(){
  var d=todayIso();
  S.meds.forEach(function(m){if(m.phases[0].from<d) d=m.phases[0].from;});
  if(S.created<d) d=S.created;
  Object.keys(S.days).forEach(function(k){if(k<d) d=k;});
  S.bp.forEach(function(r){if(r.date<d) d=r.date;});
  return d;
}
function phaseText(m,p){
  var parts=[];
  sortedSlots().forEach(function(sl){var d=p.doses[sl.id]; if(d>0) parts.push(sl.name+' '+num(d)+' '+m.unit);});
  var s=parts.join(', ')||'bez dawek';
  if(p.dow.length<7) s+=' ('+DOW_ORDER.filter(function(d){return p.dow.indexOf(d)>=0;}).map(function(d){return DOW[d];}).join(', ')+')';
  return s;
}
function upsertPhase(m,from,doses,dow){
  var eff=phaseAt(m,from);
  if(eff&&sameDoses(eff.doses,doses)&&sameArr(eff.dow,dow)) return;
  m.phases=m.phases.filter(function(p){return p.from!==from;});
  m.phases.push({from:from,doses:doses,dow:dow});
  m.phases.sort(function(a,b){return a.from<b.from?-1:(a.from>b.from?1:0);});
}

/* ---------- Widok: leki ---------- */
var sel=todayIso();
var view='meds';

function stripRange(){
  var t=todayIso(), from=addDays(t,-30), first=firstDate();
  if(first>from) from=first;
  var to=addDays(t,7);
  if(sel<from) from=sel;
  if(sel>to) to=sel;
  return [from,to];
}
function renderStrip(){
  var el=$('strip'), r=stripRange(), h='', d=r[0], t=todayIso();
  while(d<=r[1]){
    var st=dayStats(d), dt=parseIso(d), done=st.tot>0&&st.n===st.tot;
    var lab=S.events.some(function(e){return e.date===d;})?'<i class="ph ph-flask" aria-hidden="true"></i>':'';
    var txt=st.tot===0?'':(done?'<i class="ph-fill ph-check-circle" aria-hidden="true"></i>':st.n+'/'+st.tot);
    h+='<button type="button" class="chip'+(done?' done':'')+'" role="tab" aria-selected="'+(d===sel)+'" data-act="day" data-iso="'+d+'" aria-label="'+esc(longDay(d))+(st.tot?', przyjęto '+st.n+' z '+st.tot:'')+'">'
      +'<span class="dw">'+(d===t?'dziś':DOW[dt.getDay()])+'</span><span class="dn">'+pad(dt.getDate())+'</span><span class="st">'+lab+txt+'</span></button>';
    d=addDays(d,1);
  }
  el.innerHTML=h;
  var on=el.querySelector('[aria-selected="true"]');
  if(on) el.scrollLeft=on.offsetLeft-(el.clientWidth-on.offsetWidth)/2;
}
function needBackup(){
  var has=S.bp.length>0||Object.keys(S.days).length>0;
  if(!has) return null;
  if(!S.lastBackup) return daysBetween(S.created,todayIso())>=3?'nigdy':null;
  var n=daysBetween(S.lastBackup,todayIso());
  return n>=7?(n+' dni temu'):null;
}
function eventsHTML(iso,rec){
  var h='';
  S.events.filter(function(e){return e.date===iso;}).forEach(function(e){
    h+='<div class="alert" role="note"><i class="ph-fill ph-calendar-check" aria-hidden="true"></i><div><b>'+esc(e.title)+'</b>'+(e.note?'<p>'+esc(e.note)+'</p>':'')
      +(e.field?'<label class="f" for="ev_'+esc(e.id)+'" style="margin:10px 0 4px">'+esc(e.field)+'</label><input class="in mono" id="ev_'+esc(e.id)+'" data-ev="'+esc(e.id)+'" inputmode="decimal" autocomplete="off" value="'+esc((rec.results||{})[e.id]||'')+'">':'')
      +'</div></div>';
  });
  S.events.filter(function(e){var d=daysBetween(iso,e.date); return d>=1&&d<=3;}).sort(function(a,b){return a.date<b.date?-1:1;}).forEach(function(e){
    var d=daysBetween(iso,e.date);
    h+='<div class="kline">Za '+d+(d===1?' dzień':' dni')+': '+esc(e.title)+'</div>';
  });
  return h;
}
function lateState(iso,slot){
  var t=todayIso(); if(iso>t) return '';
  if(iso<t) return 'past';
  var lm=S.settings.lateMin; if(!lm) return '';
  var p=slot.time.split(':'), now=new Date();
  return now.getHours()*60+now.getMinutes()>(+p[0])*60+(+p[1])+lm?'late':'';
}
function renderMeds(){
  var t=todayIso(), st=dayStats(sel), rec=S.days[sel]||blankDay(), pct=st.tot?Math.round(st.n/st.tot*100):0;
  var goal=S.settings.waterGoal, ml=S.settings.glassMl, w=rec.water||0;
  $('topSub').textContent=S.patient||'';
  renderStrip();
  $('jumpDate').value=sel;

  var tags='';
  if(sel===t) tags+='<span class="tag">dziś</span>';
  else if(sel>t) tags+='<span class="tag">plan</span>';
  var stats='<div class="stat"><b>'+st.n+'/'+st.tot+'</b><span>leki przyjęte</span></div>';
  if(st.hasMmol) stats+='<div class="stat"><b>'+num(st.mmolTaken)+'/'+num(st.mmolPlan)+'</b><span>potas z leków, mmol</span></div>';
  if(goal>0) stats+='<div class="stat"><b>'+num(w*ml/1000)+' l</b><span>woda</span></div>';
  $('dayHead').innerHTML='<h2>'+esc(longDay(sel))+'</h2>'+(tags?'<div class="tags">'+tags+'</div>':'')
    +(st.tot?'<div class="stats">'+stats+'</div><div class="bar" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="'+pct+'"><i style="width:'+pct+'%"></i></div>'
      +'<div class="kline"><button class="linkbtn" type="button" data-act="all" style="padding:0">Zaznacz wszystkie jako przyjęte</button></div>':'');

  var nb=needBackup();
  $('banner').innerHTML=eventsHTML(sel,rec)
    +(nb?'<div class="note"><span>Ostatnia kopia danych: '+nb+'. Dane są tylko na tym urządzeniu.</span><button class="linkbtn" type="button" data-act="goData">Zrób kopię</button></div>':'');

  var out='';
  if(!S.meds.length){
    out='<div class="block"><div class="empty">Nie dodano jeszcze żadnych leków.<br><button class="btn pri" type="button" data-act="goMeds"><i class="ph-bold ph-plus" aria-hidden="true"></i>Dodaj lek</button></div></div>';
  }else if(!st.tot){
    out='<div class="block"><div class="empty">Na ten dzień nie zaplanowano żadnych leków.</div></div>';
  }else{
    sortedSlots().forEach(function(sl){
      var items=st.items.filter(function(x){return x.slot.id===sl.id;});
      if(!items.length) return;
      var lateN=0, ls=lateState(sel,sl);
      var rows=items.map(function(x){
        var on=!!rec.taken[x.key], tm=rec.times[x.key]||'', sk=!on&&(rec.skipped||{})[x.key];
        if(!on&&!sk&&ls==='late') lateN++;
        var status=sk?'<div class="skp">Pominięta: '+esc(sk.reason)+(sk.note?', '+esc(sk.note):'')+'</div>'
          :(!on&&ls==='late'?'<div class="late">Spóźniona, pora była o '+esc(sl.time)+'</div>':(!on&&ls==='past'?'<div class="late">Nie odhaczono</div>':''));
        return '<div class="med'+(on?' on':'')+'" data-key="'+esc(x.key)+'">'
          +'<button type="button" class="chk" role="checkbox" aria-checked="'+on+'" aria-label="'+esc(x.med.name)+', '+num(x.dose)+' '+esc(x.med.unit)+'"><i class="ph-bold ph-check" aria-hidden="true"></i></button>'
          +'<div><div class="med-name">'+esc(x.med.name)+' <span class="dose">'+num(x.dose)+' '+esc(x.med.unit)+'</span></div>'
          +(x.med.substance?'<div class="med-sub">'+esc(x.med.substance)+'</div>':'')
          +(x.med.end?'<div class="med-sub mono">Dzień '+(daysBetween(x.med.phases[0].from,sel)+1)+' z '+(daysBetween(x.med.phases[0].from,x.med.end)+1)+'</div>':'')
          +status+(x.med.note?'<div class="med-ins">'+esc(x.med.note)+'</div>':'')+'</div>'
          +(on?'<input class="tm" type="time" data-key="'+esc(x.key)+'" value="'+esc(tm)+'" aria-label="Godzina przyjęcia: '+esc(x.med.name)+'">':(sel<=t?'<button type="button" class="skip" data-act="skip" data-key="'+esc(x.key)+'" aria-label="'+(sk?'Zmień powód pominięcia: ':'Pomiń dawkę: ')+esc(x.med.name)+'">'+(sk?'Zmień':'Pomiń')+'</button>':'<span></span>'))
          +'</div>';
      }).join('');
      out+='<section class="slot"><div class="slot-h"><h3><i class="ph '+slotIcon(sl.time)+'" aria-hidden="true"></i>'+esc(sl.name)+'</h3><span class="hint">'+(lateN?'<span class="flag">spóźnione: '+lateN+'</span> ':'')+esc(sl.time)+'</span></div><div class="list">'+rows+'</div></section>';
    });
  }
  $('slots').innerHTML=out;

  var wb='';
  if(goal>0){
    for(var i=1;i<=goal;i++) wb+='<button type="button" data-act="water" data-n="'+i+'" class="'+(i<=w?'on':'')+'" aria-label="'+i+' szklanek" aria-pressed="'+(i<=w)+'"><i class="ph-fill ph-drop" aria-hidden="true"></i></button>';
    $('waterBlock').innerHTML='<div class="row"><h3>Woda</h3><span>'+num(w*ml/1000)+' l z '+num(goal*ml/1000)+' l</span></div><div class="water">'+wb+'</div><p class="muted" style="margin:8px 0 0">Jedna szklanka to '+ml+' ml.</p>';
  }else $('waterBlock').innerHTML='';
  $('waterBlock').hidden=goal<=0;
}
function loadDayInputs(){$('dayNote').value=(S.days[sel]||blankDay()).note||'';}

function stamp(iso,slot){return iso===todayIso()?hhmm(new Date()):slot.time;}
function toggleKey(key){
  if(sel>todayIso()){toast('Nie można odhaczać dawek z przyszłości.');return;}
  var item=scheduleFor(sel).filter(function(x){return x.key===key;})[0]; if(!item) return;
  var r=dayRec(sel);
  if(r.taken[key]){delete r.taken[key]; delete r.times[key];}
  else{
    r.taken[key]=1; r.times[key]=stamp(sel,item.slot); if(r.skipped) delete r.skipped[key];
    if(item.med.timerMin>0&&sel===todayIso()) startTimer(item.med);
  }
  save(); renderMeds();
}
function markAll(){
  if(sel>todayIso()){toast('Nie można odhaczać dawek z przyszłości.');return;}
  var r=dayRec(sel);
  scheduleFor(sel).forEach(function(x){if(!r.taken[x.key]){r.taken[x.key]=1; r.times[x.key]=stamp(sel,x.slot); if(r.skipped) delete r.skipped[x.key];}});
  save(); renderMeds();
}

/* ---------- Stoper ---------- */
var tInt=null;
function fmt(ms){var s=Math.max(0,Math.ceil(ms/1000));return pad(Math.floor(s/60))+':'+pad(s%60);}
function startTimer(m){S.timerEnd=Date.now()+m.timerMin*60000; S.timerMed=m.name; S.timerNote=m.note||''; save(); tickTimer(false);}
function tickTimer(silent){
  clearInterval(tInt);
  var box=$('timer'), left=S.timerEnd-Date.now();
  if(!S.timerEnd||left<=0){
    if(S.timerEnd){S.timerEnd=0; save(); if(!silent) toast('Odliczanie po '+S.timerMed+' zakończone.');}
    box.hidden=true; return;
  }
  box.hidden=false;
  $('tmTitle').textContent='Po przyjęciu: '+S.timerMed;
  $('tmSub').textContent=S.timerNote||'Odliczanie od przyjęcia dawki.';
  $('tmLeft').textContent=fmt(left);
  tInt=setInterval(function(){tickTimer(false);},1000);
}

/* ---------- Ciśnienie ---------- */
var ctxSel='rano', bpRange=7, pendingDel=null;
function defaultCtx(){var h=new Date().getHours(); return h<11?'rano':(h>=17?'wieczór':'inne');}
function setCtx(c){
  ctxSel=c;
  Array.prototype.forEach.call($('bpCtx').querySelectorAll('button'),function(b){b.setAttribute('aria-pressed',String(b.dataset.ctx===c));});
}
function resetBpForm(full){
  var now=new Date();
  $('bpDate').value=todayIso(); $('bpTime').value=hhmm(now);
  setCtx(defaultCtx());
  if(full){$('bpSys').value='';$('bpDia').value='';$('bpPulse').value='';$('bpNote').value='';}
  $('bpErr').textContent='';
}
function flagOf(r){
  var c=S.settings;
  if(r.sys>=c.hiSys||r.dia>=c.hiDia) return 'podwyższone';
  if(r.sys<c.loSys||r.dia<c.loDia) return 'niskie';
  return '';
}
function thrText(){var c=S.settings; return 'podwyższone od '+c.hiSys+'/'+c.hiDia+', niskie poniżej '+c.loSys+'/'+c.loDia;}
function avg(a){return a.length?Math.round(a.reduce(function(x,y){return x+y;},0)/a.length):null;}
function statsOf(list){
  var pulses=list.filter(function(r){return r.pulse;}).map(function(r){return r.pulse;});
  function sub(c){var l=list.filter(function(r){return r.ctx===c;}); return {n:l.length,s:avg(l.map(function(r){return r.sys;})),d:avg(l.map(function(r){return r.dia;}))};}
  var sy=list.map(function(r){return r.sys;}), di=list.map(function(r){return r.dia;});
  return {n:list.length,s:avg(sy),d:avg(di),p:avg(pulses),
    maxS:sy.length?Math.max.apply(null,sy):null,minS:sy.length?Math.min.apply(null,sy):null,
    maxD:di.length?Math.max.apply(null,di):null,minD:di.length?Math.min.apply(null,di):null,
    rano:sub('rano'),wieczor:sub('wieczór')};
}
function byTime(a,b){var x=a.date+a.time,y=b.date+b.time; return x<y?-1:(x>y?1:0);}
function bpInRange(){
  var list=S.bp.slice().sort(byTime);
  if(bpRange===0) return list;
  var from=addDays(todayIso(),-(bpRange-1));
  return list.filter(function(r){return r.date>=from;});
}

function chartSVG(list){
  if(!list.length) return '';
  var W=360,H=200,pl=34,pr=10,pt=10,pb=26;
  var P=list.map(function(r){return {t:new Date(r.date+'T'+(r.time||'12:00')).getTime(),s:r.sys,d:r.dia};}).sort(function(a,b){return a.t-b.t;});
  var dMin=list.reduce(function(m,r){return r.date<m?r.date:m;},list[0].date);
  var dMax=list.reduce(function(m,r){return r.date>m?r.date:m;},list[0].date);
  var nDays=daysBetween(dMin,dMax)+1;
  var t0=parseIso(dMin).getTime(), t1=parseIso(addDays(dMax,1)).getTime();
  var lo=Math.floor((Math.min.apply(null,P.map(function(p){return p.d;}))-10)/10)*10;
  var hi=Math.ceil((Math.max.apply(null,P.map(function(p){return p.s;}))+10)/10)*10;
  function X(t){return pl+(t-t0)/(t1-t0)*(W-pl-pr);}
  function Y(v){return pt+(hi-v)/(hi-lo)*(H-pt-pb);}
  var g='', step=(hi-lo)>80?20:10, v, i;
  for(v=Math.ceil(lo/step)*step; v<=hi; v+=step){
    g+='<line class="gr" x1="'+pl+'" x2="'+(W-pr)+'" y1="'+Y(v).toFixed(1)+'" y2="'+Y(v).toFixed(1)+'"/><text class="ax" x="'+(pl-6)+'" y="'+(Y(v)+3.5).toFixed(1)+'" text-anchor="end">'+v+'</text>';
  }
  var every=Math.ceil(nDays/7);
  for(i=0;i<nDays;i++){
    var day=addDays(dMin,i), xs=X(parseIso(day).getTime()), xe=X(parseIso(addDays(day,1)).getTime());
    if(i>0&&nDays<=45) g+='<line class="sepv" x1="'+xs.toFixed(1)+'" x2="'+xs.toFixed(1)+'" y1="'+pt+'" y2="'+(H-pb)+'"/>';
    if(i%every===0) g+='<text class="ax" x="'+((xs+xe)/2).toFixed(1)+'" y="'+(H-8)+'" text-anchor="middle">'+shortDate(day)+'</text>';
  }
  function pts(key){return P.map(function(p){return X(p.t).toFixed(1)+','+Y(p[key]).toFixed(1);}).join(' ');}
  if(P.length>1) g+='<polyline class="l-dia" points="'+pts('d')+'"/><polyline class="l-sys" points="'+pts('s')+'"/>';
  var rad=P.length>60?2:3.5;
  P.forEach(function(p){
    g+='<circle class="p-dia" cx="'+X(p.t).toFixed(1)+'" cy="'+Y(p.d).toFixed(1)+'" r="'+rad+'"/><circle class="p-sys" cx="'+X(p.t).toFixed(1)+'" cy="'+Y(p.s).toFixed(1)+'" r="'+rad+'"/>';
  });
  return '<svg class="chart" viewBox="0 0 '+W+' '+H+'" role="img" aria-label="Wykres ciśnienia skurczowego i rozkurczowego w czasie">'+g+'</svg>';
}
function renderBP(){
  var list=bpInRange(), st=statsOf(list);
  var seg='<div class="seg" role="group" aria-label="Zakres" style="margin-bottom:14px">'
    +[[7,'7 dni'],[30,'30 dni'],[0,'Wszystko']].map(function(x){return '<button type="button" data-act="bpRange" data-r="'+x[0]+'" aria-pressed="'+(bpRange===x[0])+'">'+x[1]+'</button>';}).join('')+'</div>';
  $('bpSummary').innerHTML=(S.bp.length?seg:'')+(list.length
    ? '<h3>Podsumowanie</h3><div class="sumrow">'
      +'<div class="stat"><b>'+st.s+'/'+st.d+'</b><span>średnie ciśnienie</span></div>'
      +'<div class="stat"><b>'+st.n+'</b><span>pomiarów</span></div>'
      +'<div class="stat"><b>'+(st.p==null?'brak':st.p)+'</b><span>średnie tętno</span></div></div>'
      +'<p class="muted" style="margin:10px 0 0">'
      +(st.rano.n?'Rano: '+st.rano.s+'/'+st.rano.d+' ('+st.rano.n+'). ':'')
      +(st.wieczor.n?'Wieczór: '+st.wieczor.s+'/'+st.wieczor.d+' ('+st.wieczor.n+'). ':'')
      +'Oznaczenia: '+thrText()+'. Ocenia lekarz.</p>'
    : (S.bp.length?'<p class="muted">Brak pomiarów w tym okresie.</p>':''));
  $('bpChartBox').hidden=!list.length;
  $('bpChart').innerHTML=chartSVG(list);

  if(!S.bp.length){
    $('bpList').innerHTML='<div class="empty">Brak pomiarów. Wpisz pierwszy wynik w formularzu powyżej.</div>';
    return;
  }
  var groups={}, order=[];
  list.slice().reverse().forEach(function(r){if(!groups[r.date]){groups[r.date]=[];order.push(r.date);} groups[r.date].push(r);});
  $('bpList').innerHTML=order.length?'<h3>Zapisane pomiary</h3>'+order.map(function(date){
    var rows=groups[date].map(function(r){
      var f=flagOf(r);
      var meta=[r.pulse?'tętno '+r.pulse:'',r.ctx,r.note].filter(Boolean).map(esc).join(', ');
      var del=pendingDel===r.id
        ? '<div class="delrow"><button type="button" class="yes" data-act="bpdelok" data-id="'+esc(r.id)+'">Usuń</button><button type="button" data-act="bpdelno">Anuluj</button></div>'
        : '<button type="button" class="tbtn" data-act="bpdel" data-id="'+esc(r.id)+'" aria-label="Usuń pomiar z '+esc(r.time)+'">Usuń</button>';
      return '<div class="rd"><div class="rd-t">'+esc(r.time)+'</div><div><div class="rd-v">'+r.sys+'<em>/</em>'+r.dia+'</div><div class="rd-m">'+meta+(f?(meta?' ':'')+'<span class="flag">'+f+'</span>':'')+'</div></div>'+del+'</div>';
    }).join('');
    return '<div class="daygroup"><h4>'+esc(longDay(date))+'</h4><div class="list">'+rows+'</div></div>';
  }).join(''):'';
}
function submitBP(e){
  e.preventDefault();
  var sys=parseInt($('bpSys').value,10), dia=parseInt($('bpDia').value,10);
  var pRaw=$('bpPulse').value.trim(), pulse=pRaw===''?null:parseInt(pRaw,10);
  var date=$('bpDate').value, time=$('bpTime').value, err=$('bpErr');
  if(isNaN(sys)||isNaN(dia)){err.textContent='Wpisz ciśnienie skurczowe i rozkurczowe.'; return;}
  if(sys<60||sys>260||dia<30||dia>160){err.textContent='Wartości poza zakresem (skurczowe 60-260, rozkurczowe 30-160).'; return;}
  if(sys<=dia){err.textContent='Skurczowe musi być większe od rozkurczowego.'; return;}
  if(pulse!==null&&(isNaN(pulse)||pulse<30||pulse>220)){err.textContent='Tętno powinno mieścić się w zakresie 30-220.'; return;}
  if(!date||!time){err.textContent='Uzupełnij datę i godzinę.'; return;}
  S.bp.push({id:uid(),date:date,time:time,sys:sys,dia:dia,pulse:pulse,ctx:ctxSel,note:$('bpNote').value.trim()});
  save(); resetBpForm(true); renderBP();
  toast('Zapisano pomiar '+sys+'/'+dia);
}

/* ---------- Raport ---------- */
var rpPreset=7, rangeFrom='', rangeTo='';
function rangeNow(){
  var t=todayIso(), r;
  if(rangeFrom||rangeTo) r={from:rangeFrom||firstDate(),to:rangeTo||t};
  else if(rpPreset===0) r={from:firstDate(),to:t};
  else r={from:addDays(t,-(rpPreset-1)),to:t};
  if(r.from>r.to) r={from:r.to,to:r.from};
  return r;
}
function dayList(from,to){var out=[],d=from,n=0; while(d<=to&&n<800){out.push(d); d=addDays(d,1); n++;} return out;}
function reportHTML(){
  var R=rangeNow(), t=todayIso(), c=S.settings;
  var list=S.bp.filter(function(r){return r.date>=R.from&&r.date<=R.to;}).sort(byTime);
  var st=statsOf(list);
  var P=c.rp, tt=[]; if(P.bp) tt.push('ciśnienia'); if(P.meds) tt.push('przyjmowania leków');
  var h='<h2 class="rp-title">'+(tt.length?'Dziennik '+tt.join(' i '):(P.labs?'Wyniki badań':'Raport dla lekarza'))+'</h2>'
    +'<div class="rp-meta">Pacjent: <b>'+esc(S.patient||'bez imienia')+'</b><br>Okres: '+plDate(R.from)+' do '+plDate(R.to)
    +(c.reportNote?'<br>'+esc(c.reportNote):'')+'</div>';
  if(!P.bp&&!P.labs&&!P.meds&&!P.plan&&!P.events) h+='<p class="muted">Nie wybrano żadnych sekcji. Zaznacz, co ma się znaleźć w raporcie.</p>';

  if(P.bp){
  h+='<h3>Pomiary ciśnienia</h3>';
  if(!list.length){
    h+='<p class="muted">Brak pomiarów w wybranym okresie.</p>';
  }else{
    h+='<div class="rp-sum"><div><b>'+st.s+'/'+st.d+'</b><span>średnie, mmHg</span></div><div><b>'+st.n+'</b><span>pomiarów</span></div><div><b>'+(st.p==null?'brak':st.p)+'</b><span>średnie tętno</span></div></div>'
      +'<p class="muted" style="margin:0 0 10px">Skurczowe '+st.minS+'-'+st.maxS+', rozkurczowe '+st.minD+'-'+st.maxD+'.'
      +(st.rano.n?' Rano średnio '+st.rano.s+'/'+st.rano.d+' ('+st.rano.n+').':'')
      +(st.wieczor.n?' Wieczorem średnio '+st.wieczor.s+'/'+st.wieczor.d+' ('+st.wieczor.n+').':'')+'</p>'
      +'<div class="legend"><span><i class="sw sys"></i>skurczowe</span><span><i class="sw dia"></i>rozkurczowe</span></div>'+chartSVG(list)
      +'<div class="tbl"><table style="margin-top:12px"><thead><tr><th>Data</th><th>Godz.</th><th>Ciśnienie</th><th>Tętno</th><th>Pora</th><th>Uwagi</th></tr></thead><tbody>'
      +list.map(function(r){
        var f=flagOf(r);
        return '<tr><td class="num">'+shortDate(r.date)+'</td><td class="num">'+esc(r.time)+'</td><td class="num"><b>'+r.sys+'/'+r.dia+'</b></td><td class="num">'+(r.pulse||'')+'</td><td>'+esc(r.ctx)+'</td><td>'+(f?'<b>'+f+'</b>':'')+(f&&r.note?', ':'')+esc(r.note)+'</td></tr>';
      }).join('')+'</tbody></table></div>'
      +'<p class="muted" style="margin:6px 0 0">Oznaczenia orientacyjne: '+thrText()+'.</p>';
  }
  }

  if(P.labs) h+=labsReportHTML(R,P.labsPeriod);

  var days=dayList(R.from,R.to<t?R.to:t), rows=[], planned=0, taken=0, fullDays=0, schedDays=0, hasM=false, skips=[], skipN=0, lateTaken=0;
  days.forEach(function(d){
    var s=dayStats(d), r=S.days[d]||blankDay();
    if(s.hasMmol) hasM=true;
    s.items.forEach(function(x){
      var sk=(r.skipped||{})[x.key];
      if(sk&&!r.taken[x.key]){skipN++; skips.push({d:d,name:x.med.name,slot:x.slot.name,reason:sk.reason,note:sk.note});}
      var tm=r.taken[x.key]&&r.times[x.key];
      if(tm&&c.lateMin>0&&isTime(tm)){var pp=x.slot.time.split(':'); if(+tm.slice(0,2)*60+ +tm.slice(3)>(+pp[0])*60+(+pp[1])+c.lateMin) lateTaken++;}
    });
    if(!s.tot&&!r.note&&!r.water) return;
    planned+=s.tot; taken+=s.n;
    if(s.tot){schedDays++; if(s.n===s.tot) fullDays++;}
    rows.push({d:d,s:s,r:r});
  });
  if(P.meds){
  h+='<h3>Przyjmowanie leków</h3>';
  if(!rows.length){h+='<p class="muted">Brak danych o lekach w wybranym okresie.</p>';}
  else{
    h+='<div class="rp-sum"><div><b>'+fullDays+'/'+schedDays+'</b><span>dni z kompletem</span></div><div><b>'+taken+'/'+planned+'</b><span>przyjęte dawki</span></div><div><b>'+(planned?Math.round(taken/planned*100):0)+'%</b><span>zgodność z planem</span></div></div>'
      +'<p class="muted" style="margin:0 0 10px">Pominięte dawki: '+skipN+'.'+(c.lateMin>0?' Przyjęte po czasie (ponad '+c.lateMin+' min po porze): '+lateTaken+'.':'')+'</p>'
      +'<div class="tbl"><table><thead><tr><th>Data</th><th>Leki</th>'+(hasM?'<th>Potas, mmol</th>':'')+(c.waterGoal>0?'<th>Woda</th>':'')+'<th>Notatka</th></tr></thead><tbody>'
      +rows.map(function(x){
        return '<tr><td class="num">'+shortDate(x.d)+'</td><td class="num">'+x.s.n+'/'+x.s.tot+'</td>'
          +(hasM?'<td class="num">'+(x.s.hasMmol?num(x.s.mmolTaken)+'/'+num(x.s.mmolPlan):'')+'</td>':'')
          +(c.waterGoal>0?'<td class="num">'+num((x.r.water||0)*c.glassMl/1000)+' l</td>':'')
          +'<td>'+esc(x.r.note||'')+'</td></tr>';
      }).join('')+'</tbody></table></div>';
  }

  if(skips.length){
    h+='<h3>Pominięte dawki</h3><div class="tbl"><table><thead><tr><th>Data</th><th>Lek</th><th>Pora</th><th>Powód</th></tr></thead><tbody>'
      +skips.map(function(x){return '<tr><td class="num">'+shortDate(x.d)+'</td><td>'+esc(x.name)+'</td><td>'+esc(x.slot)+'</td><td>'+esc(x.reason)+(x.note?', '+esc(x.note):'')+'</td></tr>';}).join('')+'</tbody></table></div>';
  }

  }

  var ms=S.meds.filter(function(m){return m.phases[0].from<=R.to&&(!m.end||m.end>=R.from);});
  if(P.plan&&ms.length){
    h+='<h3>Leki i dawkowanie w okresie</h3><ul>'+ms.map(function(m){
      var ph=m.phases.filter(function(p,i){var nx=m.phases[i+1]; return p.from<=R.to&&(!nx||nx.from>R.from);});
      return '<li><b>'+esc(m.name)+'</b>'+(m.substance?' ('+esc(m.substance)+')':'')+': '
        +ph.map(function(p){return 'od '+shortDate(p.from)+' '+esc(phaseText(m,p));}).join('; ')
        +(m.end&&m.end<=R.to?'; zakończono '+plDate(m.end):'')+'</li>';
    }).join('')+'</ul>';
  }

  var evs=S.events.filter(function(e){return e.date>=R.from&&e.date<=R.to;}).sort(function(a,b){return a.date<b.date?-1:1;});
  if(P.events&&evs.length){
    h+='<h3>Kontrole i wyniki</h3><ul>'+evs.map(function(e){
      var res=((S.days[e.date]||{}).results||{})[e.id];
      return '<li>'+plDate(e.date)+': '+esc(e.title)+(e.field?'. '+esc(e.field)+': '+(res?'<b>'+esc(res)+'</b>':'brak wyniku'):'')+'</li>';
    }).join('')+'</ul>';
  }
  h+='<p class="rp-foot">Wygenerowano '+plDate(t)+' '+hhmm(new Date())+'. Dane wpisał pacjent, raport nie zawiera oceny klinicznej.</p>';
  return h;
}
function renderReport(){
  var R=rangeNow();
  $('rpFrom').value=R.from; $('rpTo').value=R.to;
  var custom=!!(rangeFrom||rangeTo);
  Array.prototype.forEach.call($('rpPresets').querySelectorAll('button'),function(b){b.setAttribute('aria-pressed',String(!custom&&+b.dataset.p===rpPreset));});
  var P=S.settings.rp;
  Array.prototype.forEach.call($('rpParts').querySelectorAll('[data-rp]'),function(i){i.checked=!!P[i.dataset.rp];});
  $('rpLabsSub').hidden=!P.labs;
  $('printBtn').disabled=!(P.bp||P.labs||P.meds||P.plan||P.events);
  $('report').innerHTML=reportHTML();
  renderAi();
}

/* ---------- Raport dla AI ---------- */
/* Tekst w Markdown z poleceniem dla modelu i danymi z aplikacji. Powstaje tylko na urządzeniu; użytkownik sam go kopiuje lub zapisuje. */
function mdCell(x){return String(x==null?'':x).replace(/\|/g,'/').replace(/\s*\n\s*/g,' ').trim();}
function mdTable(head,rows){
  if(!rows.length) return '';
  return '| '+head.join(' | ')+' |\n|'+head.map(function(){return ' --- |';}).join('')+'\n'+rows.map(function(r){return '| '+r.map(mdCell).join(' | ')+' |';}).join('\n')+'\n';
}
function minOf(t){return (+t.slice(0,2))*60+(+t.slice(3));}
function offText(m){return (m>0?'+':(m<0?'−':''))+Math.abs(m)+' min';}
function aiPlanLines(iso){
  var by={}, out=[];
  scheduleFor(iso).forEach(function(x){(by[x.slot.id]=by[x.slot.id]||[]).push(x.med.name+' '+num(x.dose)+' '+x.med.unit);});
  sortedSlots().forEach(function(sl){if(by[sl.id]) out.push('- '+sl.time+' '+sl.name+': '+by[sl.id].join('; '));});
  return out.join('\n');
}
function aiAdherence(R){
  var t=todayIso(), now=new Date(), nowMin=now.getHours()*60+now.getMinutes(), map={}, order=[];
  dayList(R.from,R.to<t?R.to:t).forEach(function(d){
    var r=S.days[d]||blankDay();
    scheduleFor(d).forEach(function(x){
      if(d===t&&minOf(x.slot.time)>nowMin&&!r.taken[x.key]) return; /* dzisiejsza dawka, której pora jeszcze nie minęła */
      var a=map[x.key]; if(!a){a=map[x.key]={med:x.med,slot:x.slot,plan:0,taken:0,skipped:0,late:0,offs:[],reasons:{}}; order.push(x.key);}
      a.plan++;
      if(r.taken[x.key]){
        a.taken++; var tm=r.times[x.key];
        if(isTime(tm)){var off=minOf(tm)-minOf(x.slot.time); a.offs.push(off); if(S.settings.lateMin>0&&off>S.settings.lateMin) a.late++;}
      }else if((r.skipped||{})[x.key]){a.skipped++; var rs=r.skipped[x.key].reason; a.reasons[rs]=(a.reasons[rs]||0)+1;}
    });
  });
  return order.map(function(k){
    var a=map[k], miss=a.plan-a.taken-a.skipped, avgOff=a.offs.length?Math.round(a.offs.reduce(function(p,q){return p+q;},0)/a.offs.length):null;
    var rs=Object.keys(a.reasons).map(function(r){return r+' ('+a.reasons[r]+')';}).join(', ');
    return [a.med.name,a.slot.name+' '+a.slot.time,a.plan,a.taken,a.skipped+(rs?': '+rs:''),miss,a.late,avgOff===null?'brak godzin':offText(avgOff)+' (min '+offText(Math.min.apply(null,a.offs))+', max '+offText(Math.max.apply(null,a.offs))+')'];
  });
}
function aiReportText(){
  var c=S.settings, A=c.ai, R=rangeNow(), t=todayIso(), L=[], i;
  function P(s){L.push(s);}
  P('# Raport o lekach do analizy przez AI');
  P('');
  P('Wygenerowano: '+plDate(t)+' '+hhmm(new Date())+' w aplikacji Asystent leków '+APP_VERSION+'. Okres danych: '+plDate(R.from)+' – '+plDate(R.to)+'.');
  P('');
  P('## Twoje zadanie');
  P('');
  P('Jesteś doświadczonym farmaceutą klinicznym. Poniżej są dane pacjenta wpisane przez niego samego w aplikacji (mogą zawierać błędy i luki). Przeanalizuj je i odpowiedz po polsku, w tej kolejności:');
  P('');
  P('1. **Interakcje lek–lek:** każda para lub grupa leków z istotną interakcją, z oceną nasilenia (poważna, umiarkowana, słaba), mechanizmem i tym, co zwykle się robi (monitorowanie, rozdzielenie w czasie, rozmowa z lekarzem).');
  P('2. **Interakcje z jedzeniem, napojami i używkami** wynikające z tych leków (np. grejpfrut, nabiał, kawa i herbata, alkohol, produkty bogate w potas lub witaminę K).');
  P('3. **Pory dnia i sposób przyjmowania:** czy pory przyjmowania są dobrane właściwie względem posiłków i względem siebie nawzajem (wchłanianie, odstępy między lekami, leki przyjmowane na czczo lub po posiłku, leki moczopędne i nasenne a pora dnia). Podaj konkretną propozycję zmiany harmonogramu do omówienia z lekarzem.');
  P('4. **Duplikaty i dawki:** powtarzające się substancje czynne (także pod różnymi nazwami handlowymi), dawki poza typowym zakresem, leki wymagające ostrożności w kontekście podanych badań i choroby.');
  P('5. **Powiązania z wynikami badań i ciśnieniem:** czy wyniki lub pomiary mogą wynikać z leków albo wymagają zmiany ich przyjmowania.');
  P('6. **Systematyczność:** co wynika z pominiętych, spóźnionych i nieodhaczonych dawek i jakie mogą być tego skutki.');
  P('7. **Pytania do lekarza lub farmaceuty** do zadania przy najbliższej wizycie.');
  P('');
  P('Zasady odpowiedzi:');
  P('- Pracuj na substancjach czynnych. Jeśli nazwa handlowa jest niejednoznaczna albo jej nie znasz, zapytaj lub zaznacz to, zamiast zgadywać.');
  P('- Przy każdym wniosku podaj pewność (wysoka, średnia, niska) i na czym się opiera (np. Charakterystyka Produktu Leczniczego, wytyczne). Nie wymyślaj źródeł.');
  P('- Oddziel sprawy pilne (do pilnej konsultacji) od drobnych uwag. Jeśli nie widzisz problemów, napisz to wprost.');
  P('- To analiza pomocnicza. Nie zalecaj samodzielnego odstawiania leków ani zmiany dawek bez konsultacji z lekarzem.');
  P('- Jeśli brakuje danych potrzebnych do oceny (wiek, waga, funkcja nerek, leki bez recepty, suplementy, godziny posiłków), wypisz, o co zapytać pacjenta.');
  P('');
  P('## Pacjent');
  P('');
  if(A.name&&S.patient) P('- Imię i nazwisko: '+S.patient);
  var ctx=String(A.context||'').trim();
  P('- Kontekst podany przez pacjenta (wiek, płeć, choroby, alergie, leki bez recepty, suplementy): '+(ctx?ctx.replace(/\s*\n\s*/g,' / '):'brak, nie podano'));
  P('- Przyjmowanie leków zapisuje sam pacjent. Brak odhaczenia może oznaczać pominięcie dawki albo brak wpisu.');

  var ms=S.meds.filter(function(m){return medActive(m,t);}), future=S.meds.filter(function(m){return m.phases[0].from>t;});
  var ended=S.meds.filter(function(m){return m.end&&m.end<t&&m.end>=R.from;});
  function medRow(m){
    var p=phaseAt(m,t)||m.phases[0], hint=[];
    if(m.note) hint.push(m.note);
    if(m.mmol>0) hint.push('zawiera '+num(m.mmol)+' mmol potasu w 1 '+m.unit);
    if(m.timerMin>0) hint.push('po dawce pacjent odlicza '+m.timerMin+' min');
    return [m.name,m.substance||'nie podano',phaseText(m,p),plDate(m.phases[0].from),m.end?plDate(m.end):'bez końca',hint.join('; ')];
  }
  if(A.meds){
    P('');
    P('## Leki aktualnie w planie (stan na '+plDate(t)+')');
    P('');
    if(ms.length){
      P(mdTable(['Lek','Substancja czynna (wg pacjenta)','Dawkowanie','Początek','Koniec','Wskazówka pacjenta'],ms.map(medRow)));
      P('Pory dnia to etykiety pacjenta. Nazwy (np. Śniadanie, Kolacja) sugerują związek z posiłkami, ale aplikacja nie zapisuje godzin posiłków.');
      P('');
      P('### Harmonogram dnia (co jest przyjmowane razem)');
      P('');
      var groups={}, gorder=[];
      for(i=0;i<7;i++){
        var di=addDays(t,i), wd=parseIso(di).getDay(), txt=aiPlanLines(di);
        if(!groups[txt]){groups[txt]={days:[],txt:txt}; gorder.push(txt);}
        groups[txt].days.push(wd);
      }
      gorder.forEach(function(k){
        var g=groups[k], all=g.days.length===7;
        P((all?'**Codziennie**':'**'+DOW_ORDER.filter(function(d){return g.days.indexOf(d)>=0;}).map(function(d){return DOW_FULL[d];}).join(', ')+'**')+':');
        P(g.txt||'- brak leków');
        P('');
      });
    }else P('Brak leków w planie.');
    if(future.length){P('Zaplanowane na później: '+future.map(function(m){return m.name+' (od '+plDate(m.phases[0].from)+')';}).join(', ')+'.'); P('');}
    if(ended.length){
      P('### Leki zakończone w tym okresie');
      P('');
      P(mdTable(['Lek','Substancja czynna (wg pacjenta)','Dawkowanie','Początek','Koniec','Wskazówka pacjenta'],ended.map(medRow)));
    }
  }
  if(A.history){
    var ch=S.meds.filter(function(m){return m.phases.length>1&&(!m.end||m.end>=R.from);});
    if(ch.length){
      P('');
      P('## Zmiany dawkowania w czasie');
      P('');
      ch.forEach(function(m){P('- **'+m.name+'**: '+m.phases.map(function(p){return 'od '+plDate(p.from)+' '+phaseText(m,p);}).join(' → ')+(m.end?' (zakończono '+plDate(m.end)+')':''));});
    }
  }
  if(A.adherence){
    P('');
    P('## Zgodność z planem w okresie '+plDate(R.from)+' – '+plDate(R.to));
    P('');
    var ad=aiAdherence(R);
    if(ad.length){
      P(mdTable(['Lek','Pora','Zaplanowane','Odhaczone','Pominięte świadomie (powód)','Bez odhaczenia','Spóźnione ponad '+c.lateMin+' min','Odchylenie godziny przyjęcia od pory'],ad));
      P('Odchylenie dodatnie oznacza przyjęcie później niż pora z planu.');
    }else P('Brak zaplanowanych dawek w tym okresie.');
    var notes=[], d0=dayList(R.from,R.to<t?R.to:t);
    d0.forEach(function(d){var r=S.days[d]; if(r&&r.note&&r.note.trim()) notes.push([plDate(d),r.note.trim().slice(0,300)]);});
    if(A.notes&&notes.length){
      P('');
      P('### Notatki pacjenta z poszczególnych dni');
      P('');
      P(mdTable(['Data','Notatka'],notes.slice(-30)));
    }
  }
  if(A.bp){
    var list=S.bp.filter(function(r){return r.date>=R.from&&r.date<=R.to;}).sort(byTime);
    P('');
    P('## Ciśnienie tętnicze i tętno');
    P('');
    if(list.length){
      var st=statsOf(list);
      P('- Liczba pomiarów: '+st.n+'. Średnia: '+st.s+'/'+st.d+' mmHg'+(st.p!=null?', średnie tętno '+st.p+'/min':'')+'.');
      P('- Zakres: skurczowe '+st.minS+'–'+st.maxS+', rozkurczowe '+st.minD+'–'+st.maxD+' mmHg.');
      if(st.rano.n) P('- Rano średnio '+st.rano.s+'/'+st.rano.d+' ('+st.rano.n+' '+plN(st.rano.n,'pomiar','pomiary','pomiarów')+').');
      if(st.wieczor.n) P('- Wieczorem średnio '+st.wieczor.s+'/'+st.wieczor.d+' ('+st.wieczor.n+' '+plN(st.wieczor.n,'pomiar','pomiary','pomiarów')+').');
      P('- Progi ustawione przez pacjenta: '+thrText()+'.');
      P('');
      P(mdTable(['Data','Godz.','Ciśnienie','Tętno','Pora pomiaru','Uwagi'],list.slice(-40).map(function(r){return [plDate(r.date),r.time,r.sys+'/'+r.dia,r.pulse||'',r.ctx,[flagOf(r),r.note].filter(Boolean).join('; ')];})));
      if(list.length>40) P('Pokazano 40 ostatnich z '+list.length+' pomiarów.');
    }else P('Brak pomiarów w tym okresie.');
  }
  if(A.labs){
    P('');
    P('## Wyniki badań (do '+plDate(R.to)+')');
    P('');
    var rows=[];
    S.markers.forEach(function(m){
      var all=labsOf(m.id,R.to); if(!all.length) return;
      var last=all[all.length-1], f=labFlag(last.value,m);
      rows.push([m.name,m.unit,rangeText(m)||'brak',all.slice(-8).map(function(l){return plDate(l.date)+': '+lnum(l.value);}).join('; '),f||'w zakresie']);
    });
    if(rows.length){
      P(mdTable(['Parametr','Jednostka','Zakres odniesienia','Ostatnie wyniki (od najstarszego)','Ocena ostatniego wyniku'],rows));
      P('Zakresy odniesienia wpisał pacjent (zwykle z wyniku laboratorium). Oznaczenia w aplikacji są tylko orientacyjne.');
    }else P('Brak wyników badań.');
  }
  if(A.events){
    var evs=S.events.filter(function(e){return e.date>=R.from&&e.date<=addDays(t,60);}).sort(function(a,b){return a.date<b.date?-1:1;});
    if(evs.length){
      P('');
      P('## Kontrole i terminy');
      P('');
      P(mdTable(['Data','Wydarzenie','Uwagi'],evs.map(function(e){return [plDate(e.date),e.title,e.note];})));
    }
  }
  if(A.notes){
    var free=[]; if(String(S.rules||'').trim()) free.push('Notatki i zasady pacjenta:\n\n'+S.rules.trim()); if(String(c.reportNote||'').trim()) free.push('Dopisek pacjenta do raportów: '+c.reportNote.trim());
    if(free.length){
      P('');
      P('## Notatki pacjenta (dowolny tekst)');
      P('');
      P(free.join('\n\n'));
    }
  }
  P('');
  P('## Ograniczenia danych');
  P('');
  P('- Dane pochodzą z samodzielnych wpisów pacjenta i nie były weryfikowane.');
  P('- Aplikacja nie zna leków bez recepty ani suplementów, jeśli pacjent ich nie dopisał.');
  P('- Dawki są podane w jednostkach wybranych przez pacjenta (np. tabl., sasz.), bez przeliczenia na miligramy.');
  return L.join('\n').replace(/\n{3,}/g,'\n\n')+'\n';
}
function refreshAi(){
  var el=$('aiText'); if(!el) return;
  var txt=aiReportText(); el.value=txt;
  $('aiSize').textContent='Długość: '+txt.length.toLocaleString('pl-PL')+' znaków (około '+Math.round(txt.length/3.5/100)*100+' tokenów).';
}
function renderAi(){
  var A=S.settings.ai;
  Array.prototype.forEach.call(document.querySelectorAll('[data-ai]'),function(i){i.checked=!!A[i.dataset.ai];});
  var cx=$('aiCtx'); if(cx&&document.activeElement!==cx) cx.value=A.context;
  refreshAi();
}
function aiCopy(){
  var el=$('aiText'), txt=el.value;
  function fallback(){
    try{el.focus(); el.select(); if(document.execCommand('copy')){toast('Skopiowano raport dla AI'); return;}}catch(e){}
    toast('Kopiowanie jest zablokowane. Użyj zapisu pliku.');
  }
  if(navigator.clipboard&&navigator.clipboard.writeText) navigator.clipboard.writeText(txt).then(function(){toast('Skopiowano raport dla AI');},fallback);
  else fallback();
}
function aiSave(){saveFile('leki-raport-ai-'+todayIso()+'.md',$('aiText').value,'text/markdown','Zapisano raport dla AI');}

/* ---------- Pominięte dawki ---------- */
var SKIP_REASONS=['Zapomniano o dawce','Złe samopoczucie','Brak leku','Zalecenie lekarza','Inny powód'], skEd=null;
function openSkip(key){
  var it=scheduleFor(sel).filter(function(x){return x.key===key;})[0]; if(!it) return;
  var cur=((S.days[sel]||{}).skipped||{})[key];
  skEd={key:key,reason:cur?cur.reason:'',note:cur?cur.note:''};
  $('skipBody').innerHTML='<h3>Pominięta dawka</h3><p>'+esc(it.med.name)+', '+num(it.dose)+' '+esc(it.med.unit)+', '+esc(it.slot.name)+', '+plDate(sel)+'. Zapisane powody trafią do raportu dla lekarza.</p>'
    +'<div class="pills" style="margin-top:0" id="skPills" role="group" aria-label="Powód">'+SKIP_REASONS.map(function(r){return '<button type="button" class="pill" data-act="skReason" data-r="'+esc(r)+'" aria-pressed="'+(skEd.reason===r)+'">'+esc(r)+'</button>';}).join('')+'</div>'
    +'<div><label class="f" for="skNote">Notatka (opcjonalnie)</label><input class="in" id="skNote" data-sk="note" autocomplete="off" value="'+esc(skEd.note)+'"></div>'
    +'<div class="grid2"><button class="btn" type="button" data-act="close">Anuluj</button><button class="btn pri" type="button" data-act="skSave">Zapisz</button></div>'
    +(cur?'<button class="linkbtn danger" type="button" data-act="skClear" style="justify-self:start">Cofnij pominięcie</button>':'');
  $('skipDlg').showModal();
}
function skSave(){
  var r=dayRec(sel); r.skipped=r.skipped||{};
  r.skipped[skEd.key]={reason:skEd.reason||'Bez podania powodu',note:String(skEd.note||'').trim()};
  delete r.taken[skEd.key]; delete r.times[skEd.key];
  save(); $('skipDlg').close(); renderMeds(); toast('Zapisano pominięcie dawki');
}
function skClear(){
  var r=dayRec(sel); if(r.skipped) delete r.skipped[skEd.key];
  save(); $('skipDlg').close(); renderMeds();
}

/* ---------- Dziennik badań ---------- */
var labSel=S.markers.length?S.markers[0].id:'', labEd=null, mkEd=null;
function markerById(id){for(var i=0;i<S.markers.length;i++) if(S.markers[i].id===id) return S.markers[i]; return null;}
function eventById(id){for(var i=0;i<S.events.length;i++) if(S.events[i].id===id) return S.events[i]; return null;}
function labById(id){for(var i=0;i<S.labs.length;i++) if(S.labs[i].id===id) return S.labs[i]; return null;}
function labsOf(mid,upTo){
  return S.labs.filter(function(l){return l.marker===mid&&(!upTo||l.date<=upTo);}).sort(function(a,b){return a.date<b.date?-1:(a.date>b.date?1:(a.id<b.id?-1:1));});
}
function labFlag(v,m){
  if(m.lo!==null&&v<m.lo) return 'poniżej zakresu';
  if(m.hi!==null&&v>m.hi) return 'powyżej zakresu';
  return '';
}
function rangeText(m){
  if(m.lo!==null&&m.hi!==null) return lnum(m.lo)+'-'+lnum(m.hi);
  if(m.lo!==null) return 'od '+lnum(m.lo);
  if(m.hi!==null) return 'do '+lnum(m.hi);
  return '';
}
function lnum(n){return String(Math.round(n*1000)/1000).replace('.',',');}
function setEventResult(eid,text){
  var e=eventById(eid); if(!e) return;
  var r=dayRec(e.date).results;
  if(text==='') delete r[eid]; else r[eid]=text;
}
function syncEventLab(eid){
  var e=eventById(eid), id='ev:'+eid;
  S.labs=S.labs.filter(function(l){return l.id!==id;});
  if(!e||!e.marker) return;
  var r=((S.days[e.date]||{}).results||{})[eid], n=numN(r);
  if(n!==null) S.labs.push({id:id,date:e.date,marker:e.marker,value:n,note:e.title});
}
function labChartSVG(list,m){
  if(!list.length) return '';
  var W=360,H=190,pl=42,pr=14,pt=14,pb=26, i;
  var vals=list.map(function(l){return l.value;});
  if(m.lo!==null) vals.push(m.lo);
  if(m.hi!==null) vals.push(m.hi);
  var mn=Math.min.apply(null,vals), mx=Math.max.apply(null,vals), pad=(mx-mn)*0.18||Math.abs(mx)*0.1||1;
  var lo=mn-pad, hi=mx+pad, raw=(hi-lo)/4, mag=Math.pow(10,Math.floor(Math.log(raw)/Math.LN10));
  var step=[1,2,2.5,5,10].map(function(f){return f*mag;}).filter(function(x){return x>=raw-1e-12;})[0];
  lo=Math.floor(lo/step+1e-9)*step; hi=Math.ceil(hi/step-1e-9)*step;
  if(mn>=0&&lo<0) lo=0;
  var d0=list[0].date, d1=list[list.length-1].date, span=Math.max(1,daysBetween(d0,d1)), iw=W-pl-pr;
  function X(d){return list.length===1||d0===d1?pl+iw/2:pl+daysBetween(d0,d)/span*iw;}
  function Y(v){return pt+(hi-v)/(hi-lo)*(H-pt-pb);}
  var g='', v;
  if(m.lo!==null||m.hi!==null){
    var yt=Y(m.hi!==null?Math.min(m.hi,hi):hi), yb=Y(m.lo!==null?Math.max(m.lo,lo):lo);
    g+='<rect class="band" x="'+pl+'" y="'+yt.toFixed(1)+'" width="'+iw+'" height="'+Math.max(0,yb-yt).toFixed(1)+'"/>';
    if(m.hi!==null) g+='<line class="bl" x1="'+pl+'" x2="'+(W-pr)+'" y1="'+Y(m.hi).toFixed(1)+'" y2="'+Y(m.hi).toFixed(1)+'"/>';
    if(m.lo!==null) g+='<line class="bl" x1="'+pl+'" x2="'+(W-pr)+'" y1="'+Y(m.lo).toFixed(1)+'" y2="'+Y(m.lo).toFixed(1)+'"/>';
  }
  for(v=lo; v<=hi+step/1000; v+=step){
    g+='<line class="gr" x1="'+pl+'" x2="'+(W-pr)+'" y1="'+Y(v).toFixed(1)+'" y2="'+Y(v).toFixed(1)+'" style="opacity:.55"/><text class="ax" x="'+(pl-6)+'" y="'+(Y(v)+3.5).toFixed(1)+'" text-anchor="end">'+lnum(Math.round(v*1000)/1000)+'</text>';
  }
  var nt=list.length===1||d0===d1?1:Math.min(5,span+1);
  for(i=0;i<nt;i++){
    var dt=nt===1?d0:addDays(d0,Math.round(i*span/(nt-1)));
    g+='<text class="ax" x="'+(nt===1?X(d0):X(dt)).toFixed(1)+'" y="'+(H-8)+'" text-anchor="'+(nt===1?'middle':(i===0?'start':(i===nt-1?'end':'middle')))+'">'+shortDate(dt)+'</text>';
  }
  if(list.length>1) g+='<polyline class="l-lab" points="'+list.map(function(l){return X(l.date).toFixed(1)+','+Y(l.value).toFixed(1);}).join(' ')+'"/>';
  list.forEach(function(l){
    var out=!!labFlag(l.value,m), x=X(l.date), y=Y(l.value);
    g+='<circle class="p-lab'+(out?' out':'')+'" cx="'+x.toFixed(1)+'" cy="'+y.toFixed(1)+'" r="4.5"/>';
    if(list.length<=10) g+='<text class="vl" x="'+Math.min(W-pr-8,Math.max(pl+8,x)).toFixed(1)+'" y="'+(y-9).toFixed(1)+'" text-anchor="middle">'+lnum(l.value)+'</text>';
  });
  return '<svg class="chart" viewBox="0 0 '+W+' '+H+'" role="img" aria-label="Wykres: '+esc(m.name)+' w czasie, z zakresem odniesienia">'+g+'</svg>';
}
var labView='all', labOpen='';
var GRP_ORDER=['Elektrolity','Nerki i metabolizm','Morfologia','Lipidy','Wątroba i tarczyca','Stan zapalny i inne'];
function sparkSVG(list){
  var pts=list.slice(-8); if(pts.length<2) return '<span class="spark"></span>';
  var vs=pts.map(function(l){return l.value;}), mn=Math.min.apply(null,vs), mx=Math.max.apply(null,vs), W=56, H=22, pd=3;
  function X(i){return pd+i*(W-2*pd)/(pts.length-1);}
  function Y(v){return mx===mn?H/2:pd+(mx-v)/(mx-mn)*(H-2*pd);}
  var last=pts[pts.length-1];
  return '<svg class="spark" viewBox="0 0 '+W+' '+H+'" aria-hidden="true"><polyline points="'+pts.map(function(l,i){return X(i).toFixed(1)+','+Y(l.value).toFixed(1);}).join(' ')+'"/><circle cx="'+X(pts.length-1).toFixed(1)+'" cy="'+Y(last.value).toFixed(1)+'" r="2.6"/></svg>';
}
function labRowHTML(x,showDate,spark){
  var m=x.m, l=x.last, f=labFlag(l.value,m), sub=[];
  if(showDate) sub.push(shortDate(l.date));
  if(rangeText(m)) sub.push('zakres '+rangeText(m));
  return '<button type="button" class="lrow'+(f?' out':'')+'" data-act="labOpen" data-id="'+esc(m.id)+'">'
    +'<div class="ln"><div class="lt">'+esc(m.name)+'</div><div class="ls">'+esc(sub.join(' · '))+'</div></div>'
    +(spark?sparkSVG(x.list):'')
    +'<div class="lv"><b>'+lnum(l.value)+'</b><span>'+esc(m.unit)+'</span>'+(f?'<em>'+f+'</em>':'')+'</div></button>';
}
function labsModel(){
  var items=S.markers.map(function(m){var list=labsOf(m.id); return {m:m,list:list,last:list.length?list[list.length-1]:null};});
  var withRes=items.filter(function(x){return x.last;}), none=items.filter(function(x){return !x.last;});
  var out=withRes.filter(function(x){return labFlag(x.last.value,x.m);}).sort(function(a,b){return a.last.date<b.last.date?1:(a.last.date>b.last.date?-1:0);});
  var rest=withRes.filter(function(x){return !labFlag(x.last.value,x.m);}), map={}, names=[];
  rest.forEach(function(x){var g=x.m.grp||'Inne'; if(!map[g]){map[g]=[]; names.push(g);} map[g].push(x);});
  names.sort(function(a,b){
    var ia=GRP_ORDER.indexOf(a), ib=GRP_ORDER.indexOf(b);
    if(ia<0) ia=a==='Inne'?999:100; if(ib<0) ib=b==='Inne'?999:100;
    return ia!==ib?ia-ib:a.localeCompare(b,'pl');
  });
  var groups=names.map(function(n){return {name:n,items:map[n]};});
  var order=out.slice(); groups.forEach(function(g){order=order.concat(g.items);});
  return {out:out,groups:groups,none:none,order:order.map(function(x){return x.m.id;}),withRes:withRes};
}
function labsOverviewHTML(){
  var M=labsModel(), h='';
  var lastDate=S.labs.reduce(function(mx,l){return l.date>mx?l.date:mx;},'');
  h+='<div class="sumrow" style="margin-top:6px"><div class="stat"><b>'+M.withRes.length+'</b><span>parametrów z wynikami</span></div>'
    +'<div class="stat"><b'+(M.out.length?' style="color:var(--warn)"':'')+'>'+M.out.length+'</b><span>poza zakresem</span></div>'
    +'<div class="stat"><b>'+(lastDate?shortDate(lastDate):'brak')+'</b><span>ostatnie badanie</span></div></div>';
  if(!M.withRes.length) h+='<div class="empty" style="margin-top:16px">Brak wyników. Dodaj pierwszy wynik albo dołącz plik z wynikami.</div>';
  if(M.out.length) h+='<div class="daygroup"><h4>Ostatni wynik poza zakresem</h4><div class="list">'+M.out.map(function(x){return labRowHTML(x,true,true);}).join('')+'</div></div>';
  M.groups.forEach(function(g){
    h+='<div class="daygroup"><h4>'+esc(g.name)+'</h4><div class="list">'+g.items.map(function(x){return labRowHTML(x,true,true);}).join('')+'</div></div>';
  });
  if(M.none.length) h+='<details class="ended" style="margin-top:18px"><summary>Parametry bez wyników ('+M.none.length+')</summary><div class="list">'+M.none.map(function(x){
    return '<button type="button" class="srow" data-act="labOpen" data-id="'+esc(x.m.id)+'"><div><div class="srow-t">'+esc(x.m.name)+'</div><div class="srow-s">'+esc(x.m.unit)+(rangeText(x.m)?', zakres '+esc(rangeText(x.m)):'')+'</div></div><span class="go">Dodaj</span></button>';}).join('')+'</div></details>';
  return h;
}
function labsByDateHTML(){
  var by={}, dates=[];
  S.labs.forEach(function(l){if(!by[l.date]){by[l.date]=[]; dates.push(l.date);} by[l.date].push(l);});
  dates.sort().reverse();
  if(!dates.length) return '<div class="empty" style="margin-top:16px">Brak wyników.</div>';
  return dates.map(function(d){
    var rows=by[d].map(function(l){var m=markerById(l.marker); return m?{m:m,last:l,list:[],ord:S.markers.indexOf(m)}:null;}).filter(Boolean).sort(function(a,b){return a.ord-b.ord;});
    var nOut=rows.filter(function(x){return labFlag(x.last.value,x.m);}).length;
    return '<div class="daygroup"><h4>'+esc(plDate(d))+' · '+rows.length+' '+plN(rows.length,'wynik','wyniki','wyników')+(nOut?', poza zakresem: '+nOut:'')+'</h4><div class="list">'+rows.map(function(x){return labRowHTML(x,false,false);}).join('')+'</div></div>';
  }).join('');
}
function labsDetailHTML(m){
  var list=labsOf(m.id), unit=m.unit?' '+m.unit:'', M=labsModel(), ix=M.order.indexOf(m.id), h='';
  h+='<div class="dnav"><button class="linkbtn" type="button" data-act="labBack">‹ Wszystkie badania</button>'
    +(ix>=0?'<div class="pn"><button class="tbtn nav" type="button" data-act="labPrev" aria-label="Poprzedni parametr"'+(ix<=0?' disabled':'')+'>‹</button><span class="muted">'+(ix+1)+' z '+M.order.length+'</span><button class="tbtn nav" type="button" data-act="labNext" aria-label="Następny parametr"'+(ix>=M.order.length-1?' disabled':'')+'>›</button></div>':'')+'</div>';
  h+='<div class="block"><div class="sec-h" style="margin-bottom:12px"><h2 style="margin:0;font-size:20px;font-weight:650">'+esc(m.name)+'</h2><button class="btn pri" type="button" data-act="labNew" style="min-height:42px;padding:0 14px">Dodaj wynik</button></div>';
  if(!list.length){
    h+='<p class="muted" style="margin:0">Brak wyników. Dodaj pierwszy wynik badania'+(rangeText(m)?', zakres odniesienia '+esc(rangeText(m)+unit):'')+'.</p>';
  }else{
    var last=list[list.length-1], prev=list.length>1?list[list.length-2]:null, f=labFlag(last.value,m);
    var dlt=prev?last.value-prev.value:null;
    h+='<div class="sumrow">'
      +'<div class="stat"><b>'+lnum(last.value)+'</b><span>ostatni wynik'+(unit?','+esc(unit):'')+', '+shortDate(last.date)+'</span></div>'
      +'<div class="stat"><b>'+(dlt===null?'brak':(dlt>0?'+':(dlt<0?'−':''))+lnum(Math.abs(dlt)))+'</b><span>'+(prev?'zmiana od '+shortDate(prev.date):'zmiana')+'</span></div>'
      +'<div class="stat"><b>'+(rangeText(m)||'brak')+'</b><span>zakres odniesienia</span></div></div>'
      +(f?'<p class="flag" style="margin:10px 0 0">Ostatni wynik jest '+f+'. Ocenia lekarz.</p>':'')
      +'<div style="margin-top:14px">'+labChartSVG(list,m)+'</div>'
      +(rangeText(m)?'<p class="muted" style="margin:6px 0 0">Zielone tło to zakres odniesienia. Jest orientacyjny: laboratoria podają własne zakresy.</p>':'');
  }
  h+='<button class="linkbtn" type="button" data-act="mkEdit" data-id="'+esc(m.id)+'" style="margin-top:8px">Zmień nazwę lub zakres odniesienia</button></div>';
  if(list.length){
    h+='<div class="daygroup"><h4>Wyniki, od najnowszego</h4><div class="list">'+list.slice().reverse().map(function(l){
      var fl=labFlag(l.value,m);
      return '<button type="button" class="srow" data-act="labEdit" data-id="'+esc(l.id)+'"><div><div class="srow-t"><span class="lab-v">'+lnum(l.value)+'</span> <span class="muted">'+esc(m.unit)+'</span>'+(fl?' <span class="flag">'+fl+'</span>':'')+'</div><div class="srow-s">'+plDate(l.date)+(l.note?', '+esc(l.note):'')+'</div></div><span class="go">Edytuj</span></button>';
    }).join('')+'</div></div>';
  }
  return h;
}
function renderLabs(){
  var el=$('labsBody'), h='';
  if(labOpen&&!markerById(labOpen)) labOpen='';
  if(labOpen){
    labSel=labOpen; h=labsDetailHTML(markerById(labOpen));
  }else{
    h='<div class="sec-h" style="margin:18px 0 12px"><h2 style="margin:0;font-size:20px;font-weight:650">Badania</h2><button class="btn pri" type="button" data-act="labNew" style="min-height:42px;padding:0 14px">Dodaj wynik</button></div>'
      +'<div class="seg s2" role="group" aria-label="Widok wyników"><button type="button" data-act="labMode" data-v="all" aria-pressed="'+(labView==='all')+'">Parametry</button><button type="button" data-act="labMode" data-v="dates" aria-pressed="'+(labView==='dates')+'">Według dat</button></div>'
      +(labView==='dates'?labsByDateHTML():labsOverviewHTML());
  }
  h+='<div style="margin-top:18px"><button class="linkbtn" type="button" data-act="import">Dołącz wyniki z pliku (np. przygotowanego z Diagnostyki)</button></div>';
  el.innerHTML=h;
}
function labNav(d){
  var o=labsModel().order, i=o.indexOf(labOpen), n=i+d;
  if(i<0||n<0||n>=o.length) return;
  labOpen=o[n]; renderLabs(); window.scrollTo(0,0);
}
function openLab(id,mid){
  if(!S.markers.length){toast('Najpierw dodaj parametr badania w Ustawieniach.'); return;}
  var l=id?labById(id):null;
  labEd=l?{id:l.id,marker:l.marker,date:l.date,value:lnum(l.value),note:l.note}:{id:null,marker:mid||labSel,date:todayIso(),value:'',note:''};
  var m=markerById(labEd.marker)||S.markers[0]; labEd.marker=m.id;
  $('labBody').innerHTML='<h3>'+(l?'Edycja wyniku':'Nowy wynik')+'</h3><div class="stack">'
    +'<div><label class="f" for="lbMarker">Parametr</label><select class="in" id="lbMarker" data-lb="marker">'+S.markers.map(function(x){return '<option value="'+esc(x.id)+'"'+(x.id===labEd.marker?' selected':'')+'>'+esc(x.name)+'</option>';}).join('')+'</select></div>'
    +'<div class="grid2"><div><label class="f" for="lbValue">Wynik <span id="lbUnit">'+esc(m.unit)+'</span></label><input class="in mono" id="lbValue" data-lb="value" inputmode="decimal" autocomplete="off" value="'+esc(labEd.value)+'"></div>'
    +'<div><label class="f" for="lbDate">Data</label><input class="in mono" type="date" id="lbDate" data-lb="date" value="'+esc(labEd.date)+'"></div></div>'
    +'<div><label class="f" for="lbNote">Notatka (opcjonalnie)</label><input class="in" id="lbNote" data-lb="note" autocomplete="off" value="'+esc(labEd.note)+'" placeholder="np. na czczo, laboratorium"></div>'
    +'<div class="err" id="lbErr" role="alert"></div>'
    +'<div class="grid2"><button class="btn" type="button" data-act="close">Anuluj</button><button class="btn pri" type="button" data-act="labSave">Zapisz</button></div>'
    +(l?'<button class="linkbtn danger" type="button" data-act="labDelete" style="justify-self:start">Usuń wynik</button>':'')+'</div>';
  $('labDlg').showModal();
}
function labSave(){
  var m=markerById(labEd.marker), n=numN(labEd.value), err=$('lbErr');
  if(!m){err.textContent='Wybierz parametr.'; return;}
  if(n===null||n<0||n>100000){err.textContent='Wpisz wynik jako liczbę, np. 4,2.'; return;}
  if(!isIso(labEd.date)){err.textContent='Wybierz datę.'; return;}
  var rec={id:labEd.id||uid(),date:labEd.date,marker:m.id,value:n,note:String(labEd.note||'').trim()};
  if(labEd.id) S.labs=S.labs.map(function(x){return x.id===labEd.id?rec:x;}); else S.labs.push(rec);
  if(rec.id.indexOf('ev:')===0) setEventResult(rec.id.slice(3),lnum(n));
  labSel=m.id; save(); $('labDlg').close(); renderAll(); toast('Zapisano wynik');
}
function labDelete(){
  var id=labEd.id;
  confirmBox('Usunąć ten wynik?','Wynik zniknie z dziennika i raportu.','Usuń',true,function(){
    S.labs=S.labs.filter(function(x){return x.id!==id;});
    if(id.indexOf('ev:')===0) setEventResult(id.slice(3),'');
    save(); $('labDlg').close(); renderAll(); toast('Usunięto wynik');
  });
}
function openMk(id){
  var m=id?markerById(id):null;
  mkEd=m?{id:m.id,name:m.name,unit:m.unit,lo:m.lo===null?'':lnum(m.lo),hi:m.hi===null?'':lnum(m.hi),grp:m.grp||''}:{id:null,name:'',unit:'',lo:'',hi:'',grp:''};
  var used=m?(S.labs.some(function(l){return l.marker===m.id;})||S.events.some(function(e){return e.marker===m.id;})):false;
  $('mkBody').innerHTML='<h3>'+(m?'Parametr badania':'Nowy parametr')+'</h3><div class="stack">'
    +'<div><label class="f" for="mkName">Nazwa</label><input class="in" id="mkName" data-mk="name" value="'+esc(mkEd.name)+'" autocomplete="off" placeholder="np. Ferrytyna"></div>'
    +'<div><label class="f" for="mkUnit">Jednostka</label><input class="in" id="mkUnit" data-mk="unit" value="'+esc(mkEd.unit)+'" autocomplete="off" placeholder="np. mmol/l"></div>'
    +'<div><label class="f" for="mkGrp">Grupa (opcjonalnie)</label><input class="in" id="mkGrp" data-mk="grp" list="mkGrpList" value="'+esc(mkEd.grp)+'" autocomplete="off" placeholder="np. Lipidy"><datalist id="mkGrpList">'+GRP_ORDER.map(function(g){return '<option value="'+esc(g)+'">';}).join('')+'</datalist></div>'
    +'<div><span class="f">Zakres odniesienia (opcjonalnie)</span><div class="grid2"><input class="in mono" id="mkLo" data-mk="lo" inputmode="decimal" value="'+esc(mkEd.lo)+'" placeholder="od" aria-label="Dolna granica"><input class="in mono" id="mkHi" data-mk="hi" inputmode="decimal" value="'+esc(mkEd.hi)+'" placeholder="do" aria-label="Górna granica"></div>'
    +'<p class="hint">Przepisz zakres z wyniku z laboratorium, bo każde podaje własny. Aplikacja tylko oznacza wartości, ocenia lekarz.</p></div>'
    +'<div class="err" id="mkErr" role="alert"></div>'
    +'<div class="grid2"><button class="btn" type="button" data-act="close">Anuluj</button><button class="btn pri" type="button" data-act="mkSave">Zapisz</button></div>'
    +(m?(used?'<p class="muted">Parametr ma wyniki lub kontrole, więc nie można go usunąć.</p>':'<button class="linkbtn danger" type="button" data-act="mkDelete" style="justify-self:start">Usuń parametr</button>'):'')+'</div>';
  $('mkDlg').showModal();
}
function mkSave(){
  var name=String(mkEd.name||'').trim(), lo=numN(mkEd.lo), hi=numN(mkEd.hi), err=$('mkErr');
  if(!name){err.textContent='Wpisz nazwę parametru.'; return;}
  if(String(mkEd.lo).trim()!==''&&lo===null){err.textContent='Dolna granica musi być liczbą.'; return;}
  if(String(mkEd.hi).trim()!==''&&hi===null){err.textContent='Górna granica musi być liczbą.'; return;}
  if(lo!==null&&hi!==null&&lo>=hi){err.textContent='Dolna granica musi być mniejsza od górnej.'; return;}
  var rec={id:mkEd.id||('m'+uid()),name:name,unit:String(mkEd.unit||'').trim(),lo:lo,hi:hi,grp:String(mkEd.grp||'').trim()};
  if(mkEd.id) S.markers=S.markers.map(function(x){return x.id===mkEd.id?rec:x;}); else S.markers.push(rec);
  labSel=rec.id; save(); $('mkDlg').close(); renderAll(); toast('Zapisano parametr');
}
function mkDelete(){
  var id=mkEd.id;
  S.markers=S.markers.filter(function(x){return x.id!==id;});
  if(labSel===id) labSel=S.markers.length?S.markers[0].id:'';
  save(); $('mkDlg').close(); renderAll(); toast('Usunięto parametr');
}
function labsReportHTML(R,onlyPeriod){
  var h='', singles=[], any=false;
  function head(){if(!any){h+='<h3>Wyniki badań</h3><p class="muted" style="margin:0 0 6px">'+(onlyPeriod?'Tylko wyniki z wybranego okresu.':'Wyniki z całej historii do końca okresu.')+' Zakresy odniesienia pochodzą z laboratorium lub są orientacyjne.</p>'; any=true;}}
  S.markers.forEach(function(m){
    var all=labsOf(m.id,R.to).filter(function(l){return !onlyPeriod||l.date>=R.from;}); if(!all.length) return;
    var list=all.slice(-12);
    if(list.length<2){singles.push({m:m,l:list[0]}); return;}
    head();
    h+='<div class="rp-lab">'+esc(m.name)+(m.unit?', '+esc(m.unit):'')+(rangeText(m)?' (zakres odniesienia '+esc(rangeText(m))+')':'')+'</div>'
      +labChartSVG(list,m)
      +'<div class="tbl"><table><thead><tr><th>Data</th><th>Wynik</th><th>Ocena</th><th>Uwagi</th></tr></thead><tbody>'
      +list.map(function(l){var f=labFlag(l.value,m); return '<tr><td class="num">'+plDate(l.date)+'</td><td class="num"><b>'+lnum(l.value)+'</b></td><td>'+(f?'<b>'+f+'</b>':'w zakresie')+'</td><td>'+esc(l.note)+'</td></tr>';}).join('')
      +'</tbody></table></div>';
  });
  if(singles.length){
    head();
    singles.sort(function(a,b){return a.l.date<b.l.date?1:(a.l.date>b.l.date?-1:a.m.name.localeCompare(b.m.name,'pl'));});
    h+='<div class="rp-lab">Pojedyncze wyniki</div><div class="tbl"><table><thead><tr><th>Data</th><th>Parametr</th><th>Wynik</th><th>Zakres</th><th>Ocena</th></tr></thead><tbody>'
      +singles.map(function(x){var f=labFlag(x.l.value,x.m); return '<tr><td class="num">'+plDate(x.l.date)+'</td><td>'+esc(x.m.name)+'</td><td class="num"><b>'+lnum(x.l.value)+'</b> '+esc(x.m.unit)+'</td><td class="num">'+esc(rangeText(x.m)||'-')+'</td><td>'+(f?'<b>'+f+'</b>':'w zakresie')+'</td></tr>';}).join('')
      +'</tbody></table></div>';
  }
  return h;
}

/* ---------- Aplikacja na Androida (Capacitor): przypomnienia, zapis plików, druk ---------- */
function isNative(){try{return !!(window.Capacitor&&window.Capacitor.isNativePlatform&&window.Capacitor.isNativePlatform());}catch(e){return false;}}
function capPlug(n){try{if(isNative()&&window.Capacitor.Plugins&&window.Capacitor.Plugins[n]) return window.Capacitor.Plugins[n];}catch(e){} return null;}
var remTimer=0, remBusy=false, remAgain=false, remInfo='';
function remId(iso,si){var q=iso.split('-'); return (Math.floor(Date.UTC(+q[0],+q[1]-1,+q[2])/86400000)%20000)*100+si+10;}
function buildReminders(){
  var out=[], now=Date.now(), rm=S.settings.remind, t0=todayIso(), slots=sortedSlots(), i;
  for(i=0;i<14;i++){
    var iso=addDays(t0,i), items=scheduleFor(iso), r=S.days[iso]||blankDay();
    slots.forEach(function(sl,si){
      var open=items.filter(function(x){return x.slot.id===sl.id&&!r.taken[x.key]&&!(r.skipped||{})[x.key];});
      if(!open.length||!isTime(sl.time)) return;
      var tp=sl.time.split(':'), dp=parseIso(iso), at=new Date(dp.getFullYear(),dp.getMonth(),dp.getDate(),+tp[0],+tp[1]-rm.lead,0,0);
      if(at.getTime()<=now+5000) return;
      out.push({id:remId(iso,si),title:'Pora na leki: '+sl.name,body:open.map(function(x){return x.med.name+' '+num(x.dose)+' '+x.med.unit;}).join(', '),schedule:{at:at,allowWhileIdle:true}});
    });
  }
  if(S.timerEnd>now+5000) out.push({id:1,title:'Minął czas po: '+(S.timerMed||'leku'),body:S.timerNote||'Możesz przejść do kolejnego kroku.',schedule:{at:new Date(S.timerEnd),allowWhileIdle:true}});
  return out;
}
function scheduleReminders(){
  var LN=capPlug('LocalNotifications'); if(!LN) return;
  if(remBusy){remAgain=true; return;}
  remBusy=true;
  var list=S.settings.remind.on?buildReminders():[], denied=false;
  Promise.resolve(S.settings.remind.on?LN.checkPermissions():{display:'granted'}).then(function(perm){
    if(perm&&perm.display!=='granted'){denied=true; list=[];}
    return LN.getPending();
  }).then(function(p){
    var ids=((p&&p.notifications)||[]).filter(function(n){return n.id!==2;}).map(function(n){return {id:n.id};});
    return ids.length?LN.cancel({notifications:ids}):null;
  }).then(function(){return list.length?LN.schedule({notifications:list}):null;})
  .then(function(){remInfo=denied?'Brak zgody na powiadomienia. Włącz je w ustawieniach telefonu.':(S.settings.remind.on?('Zaplanowane przypomnienia: '+list.length+'.'):'');},function(){remInfo='Nie udało się zaplanować przypomnień. Sprawdź uprawnienia aplikacji.';})
  .then(function(){remBusy=false; if(remAgain){remAgain=false; scheduleReminders();} var el=$('remInfo'); if(el) el.textContent=remInfo;});
}
function queueReminders(){if(!isNative()) return; clearTimeout(remTimer); remTimer=setTimeout(scheduleReminders,700);}
function remindToggle(){
  var LN=capPlug('LocalNotifications');
  if(!LN){toast('Przypomnienia działają w aplikacji na Androida.'); return;}
  if(S.settings.remind.on){S.settings.remind.on=false; save(); renderSettings(); return;}
  Promise.resolve(LN.requestPermissions()).then(function(r){
    if(!r||r.display!=='granted'){toast('Brak zgody na powiadomienia. Włącz je w ustawieniach telefonu.'); return;}
    var chk=LN.checkExactNotificationSetting?Promise.resolve(LN.checkExactNotificationSetting()):Promise.resolve({});
    return chk.then(function(e){
      if(e&&e.exact_alarm==='denied'&&LN.changeExactNotificationSetting){toast('Zezwól aplikacji na dokładne alarmy, inaczej przypomnienia mogą się spóźniać.'); return LN.changeExactNotificationSetting();}
    }).then(function(){S.settings.remind.on=true; save(); renderSettings(); toast('Przypomnienia włączone');});
  }).catch(function(){toast('Nie udało się włączyć przypomnień.');});
}
function remindTest(){
  var LN=capPlug('LocalNotifications'); if(!LN){toast('Test działa w aplikacji na Androida.'); return;}
  Promise.resolve(LN.requestPermissions()).then(function(r){
    if(!r||r.display!=='granted'){toast('Brak zgody na powiadomienia.'); return;}
    return LN.schedule({notifications:[{id:2,title:'Asystent leków',body:'Tak będzie wyglądać przypomnienie.',schedule:{at:new Date(Date.now()+8000),allowWhileIdle:true}}]}).then(function(){toast('Powiadomienie pojawi się za kilka sekund. Możesz zablokować ekran.');});
  }).catch(function(){toast('Nie udało się wysłać powiadomienia.');});
}
function saveFile(name,text,mime,okMsg){
  var FS=capPlug('Filesystem'), SH=capPlug('Share');
  if(FS&&SH){
    FS.writeFile({path:name,data:text,directory:'CACHE',encoding:'utf8'}).then(function(r){
      return SH.share({title:name,url:r.uri,dialogTitle:'Zapisz lub wyślij kopię'});
    }).then(function(){toast(okMsg);},function(e){if(!/cancel/i.test(String((e&&e.message)||e))) toast('Nie udało się zapisać pliku. Użyj opcji Kopiuj JSON.');});
    return;
  }
  var a=document.createElement('a');
  a.href=URL.createObjectURL(new Blob([text],{type:mime}));
  a.download=name; document.body.appendChild(a); a.click(); a.remove();
  setTimeout(function(){URL.revokeObjectURL(a.href);},2000);
  toast(okMsg);
}
function doPrint(){
  var P=capPlug('PrintPlugin');
  if(P){P.print({title:'Raport '+(S.patient||'')+' '+todayIso()}).catch(function(){toast('Nie udało się otworzyć drukowania.');}); return;}
  window.print();
}

/* ---------- Eksport i import ---------- */
function payload(){S.lastBackup=todayIso(); save(); return JSON.stringify({app:'asystent-lekow',version:3,exportedAt:new Date().toISOString(),data:S},null,2);}
function exportJSON(){
  try{
    saveFile('leki-cisnienie-'+todayIso()+'.json',payload(),'application/json','Pobrano plik JSON');
    if(view==='settings') renderSettings();
  }catch(e){toast('Nie udało się pobrać pliku. Użyj opcji Kopiuj JSON.');}
}
function copyJSON(){
  var txt=payload();
  if(navigator.clipboard&&navigator.clipboard.writeText){
    navigator.clipboard.writeText(txt).then(function(){toast('Skopiowano dane JSON'); if(view==='settings') renderSettings();},function(){toast('Kopiowanie jest zablokowane. Użyj pobrania pliku.');});
  }else toast('Kopiowanie nie jest dostępne. Użyj pobrania pliku.');
}
function importJSON(file){
  var fr=new FileReader();
  fr.onload=function(){handleImport(String(fr.result));};
  fr.readAsText(file);
}
function handleImport(text){
  var next;
  try{
    var o=JSON.parse(text);
    if(o&&o.app==='asystent-lekow-enc'){openDec(o); return;}
    if(o&&o.app==='asystent-lekow-wyniki'){openLabsMerge(o); return;}
    var d=o&&o.data?o.data:o;
    if(!d||typeof d!=='object'||typeof d.days!=='object') throw new Error('format');
    if(d.v!==3&&o.version!==3) throw new Error('version');
    next=normalize(d);
  }catch(e){toast('To nie jest plik kopii z tej aplikacji.'); return;}
  var nb=next.bp.length, nd=Object.keys(next.days).length, nm=next.meds.length;
  confirmBox('Wczytać tę kopię?','W pliku: '+nm+' leków, '+nd+' dni z zapisami, '+nb+' pomiarów ciśnienia. Wczytanie zastąpi wszystkie dane na tym urządzeniu. Jeśli masz tu coś ważnego, najpierw pobierz kopię.','Zastąp dane',false,function(){
    S=next; save(); applyTheme(); labOpen=''; labSel=S.markers.length?S.markers[0].id:''; sel=todayIso(); rangeFrom=''; rangeTo=''; tickTimer(true); renderAll(); toast('Wczytano kopię danych');
  });
}

function openLabsMerge(o){
  var mk=(Array.isArray(o.markers)?o.markers:[]).filter(function(x){return x&&typeof x.id==='string'&&x.id&&typeof x.name==='string'&&x.name.trim();});
  var ls=(Array.isArray(o.labs)?o.labs:[]).filter(function(x){return x&&typeof x.marker==='string'&&isIso(x.date)&&typeof x.value==='number'&&isFinite(x.value);});
  if(!ls.length){toast('W pliku nie ma wyników.'); return;}
  function known(id){return !!markerById(id)||mk.some(function(m){return m.id===id;});}
  function has(l){return S.labs.some(function(e){return e.marker===l.marker&&e.date===l.date;});}
  var fresh=ls.filter(function(l){return known(l.marker)&&!has(l);}), skipped=ls.length-fresh.length;
  var newM=mk.filter(function(m){return !markerById(m.id);}).length;
  confirmBox('Dołączyć wyniki badań?','Nowych wyników: '+fresh.length+(skipped?'. Pominę '+skipped+' już zapisanych (ten sam parametr i dzień)':'')+(newM?'. Nowych parametrów: '+newM:'')
    +'. Zakresy odniesienia parametrów z pliku zostaną ustawione według laboratorium. Twoje pozostałe dane się nie zmienią.','Dołącz',false,function(){
    mk.forEach(function(m){
      var cur=markerById(m.id);
      if(cur){cur.lo=numN(m.lo); cur.hi=numN(m.hi); if(!cur.unit&&m.unit) cur.unit=String(m.unit); if(!cur.grp&&m.grp) cur.grp=String(m.grp);}
      else S.markers.push({id:m.id,name:m.name.trim(),unit:String(m.unit||''),lo:numN(m.lo),hi:numN(m.hi),grp:String(m.grp||DEFAULT_GRP[m.id]||'').trim()});
    });
    fresh.forEach(function(l,i){S.labs.push({id:'i'+uid()+i,date:l.date,marker:l.marker,value:l.value,note:String(l.note||'')});});
    save(); renderAll(); toast('Dodano '+fresh.length+' wyników');
  });
}

/* ---------- Szyfrowana kopia (AES-GCM, klucz z hasła przez PBKDF2) ---------- */
var ENC_ITER=600000, encOut=null, encPending=null;
function cryptoOk(){return !!(window.crypto&&crypto.subtle&&window.TextEncoder);}
function b64(buf){var a=new Uint8Array(buf),s='',i; for(i=0;i<a.length;i++) s+=String.fromCharCode(a[i]); return btoa(s);}
function unb64(t){var s=atob(t),a=new Uint8Array(s.length),i; for(i=0;i<s.length;i++) a[i]=s.charCodeAt(i); return a;}
function deriveKey(pw,salt,iter){
  return crypto.subtle.importKey('raw',new TextEncoder().encode(pw),'PBKDF2',false,['deriveKey']).then(function(k){
    return crypto.subtle.deriveKey({name:'PBKDF2',salt:salt,iterations:iter,hash:'SHA-256'},k,{name:'AES-GCM',length:256},false,['encrypt','decrypt']);
  });
}
function encryptText(pw,text){
  var salt=crypto.getRandomValues(new Uint8Array(16)), iv=crypto.getRandomValues(new Uint8Array(12));
  return deriveKey(pw,salt,ENC_ITER).then(function(key){
    return crypto.subtle.encrypt({name:'AES-GCM',iv:iv},key,new TextEncoder().encode(text));
  }).then(function(ct){
    return JSON.stringify({app:'asystent-lekow-enc',v:1,kdf:'PBKDF2-SHA256',iter:ENC_ITER,cipher:'AES-256-GCM',salt:b64(salt),iv:b64(iv),ct:b64(ct)});
  });
}
function decryptText(pw,o){
  var iter=Math.round(+o.iter);
  if(!(iter>=100000&&iter<=5000000)||typeof o.salt!=='string'||typeof o.iv!=='string'||typeof o.ct!=='string') return Promise.reject(new Error('format'));
  return deriveKey(pw,unb64(o.salt),iter).then(function(key){
    return crypto.subtle.decrypt({name:'AES-GCM',iv:unb64(o.iv)},key,unb64(o.ct));
  }).then(function(buf){return new TextDecoder().decode(buf);});
}
function openEnc(){
  if(!cryptoOk()){toast('Szyfrowanie wymaga połączenia HTTPS lub otwarcia pliku lokalnie.'); return;}
  encOut=null;
  $('encBody').innerHTML='<h3>Szyfrowana kopia</h3><p>Dane zostaną zaszyfrowane na tym urządzeniu hasłem, które wpiszesz poniżej. Plik możesz bezpiecznie zapisać w chmurze (Dysk Google, Filen). Hasła nie da się odzyskać: bez niego plik jest nie do odczytania.</p>'
    +'<div class="stack"><div><label class="f" for="encPw">Hasło (min. 8 znaków)</label><input class="in" type="password" id="encPw" autocomplete="new-password" autocapitalize="off"></div>'
    +'<div><label class="f" for="encPw2">Powtórz hasło</label><input class="in" type="password" id="encPw2" autocomplete="new-password" autocapitalize="off"></div>'
    +'<div class="err" id="encErr" role="alert"></div>'
    +'<div class="grid2"><button class="btn" type="button" data-act="close">Anuluj</button><button class="btn pri" type="button" data-act="encGo">Zaszyfruj</button></div></div>';
  $('encDlg').showModal();
}
function encGo(){
  var pw=$('encPw').value, pw2=$('encPw2').value, err=$('encErr');
  if(pw.length<8){err.textContent='Hasło musi mieć co najmniej 8 znaków.'; return;}
  if(pw!==pw2){err.textContent='Hasła się różnią.'; return;}
  err.textContent=''; var btn=document.querySelector('[data-act=encGo]'); btn.disabled=true; btn.textContent='Szyfruję…';
  encryptText(pw,payload()).then(function(json){
    var name='leki-'+todayIso()+'.zaszyfrowane.json', file=null;
    try{file=new File([json],name,{type:'application/json'});}catch(e){}
    encOut={json:json,name:name,file:file};
    var canShare=!!(file&&navigator.canShare&&navigator.canShare({files:[file]}));
    $('encBody').innerHTML='<h3>Kopia gotowa</h3><p>Plik jest zaszyfrowany. Zapisz go w chmurze albo na dysku. Zapamiętaj hasło, bo bez niego plik nie otworzy się nigdzie.</p>'
      +'<div class="stack">'+(canShare?'<button class="btn pri wide" type="button" data-act="encShare">Udostępnij (Dysk Google, Filen…)</button>':'')
      +'<button class="btn'+(canShare?'':' pri')+' wide" type="button" data-act="encSave">Pobierz plik</button>'
      +'<button class="linkbtn" type="button" data-act="close" style="justify-self:start">Zamknij</button></div>';
    if(view==='settings') renderSettings();
  },function(){btn.disabled=false; btn.textContent='Zaszyfruj'; err.textContent='Nie udało się zaszyfrować na tym urządzeniu.';});
}
function encSave(){
  try{saveFile(encOut.name,encOut.json,'application/json','Pobrano zaszyfrowaną kopię');}
  catch(e){toast('Nie udało się pobrać pliku.');}
}
function encShare(){
  if(!encOut||!encOut.file) return;
  navigator.share({files:[encOut.file],title:'Kopia Asystenta leków (zaszyfrowana)'}).then(function(){toast('Udostępniono');},function(e){if(e&&e.name!=='AbortError') toast('Udostępnianie nie powiodło się. Użyj pobrania pliku.');});
}
function openDec(o){
  if(!cryptoOk()){toast('Odszyfrowanie wymaga połączenia HTTPS lub otwarcia pliku lokalnie.'); return;}
  encPending=o;
  $('encBody').innerHTML='<h3>Zaszyfrowana kopia</h3><p>Wpisz hasło użyte przy tworzeniu kopii.</p>'
    +'<div class="stack"><div><label class="f" for="decPw">Hasło</label><input class="in" type="password" id="decPw" autocomplete="current-password" autocapitalize="off"></div>'
    +'<div class="err" id="encErr" role="alert"></div>'
    +'<div class="grid2"><button class="btn" type="button" data-act="close">Anuluj</button><button class="btn pri" type="button" data-act="decGo">Odszyfruj</button></div></div>';
  $('encDlg').showModal();
}
function decGo(){
  var pw=$('decPw').value, err=$('encErr'), btn=document.querySelector('[data-act=decGo]');
  if(!pw){err.textContent='Wpisz hasło.'; return;}
  btn.disabled=true; btn.textContent='Odszyfrowuję…'; err.textContent='';
  decryptText(pw,encPending).then(function(text){
    $('encDlg').close(); encPending=null; handleImport(text);
  },function(){btn.disabled=false; btn.textContent='Odszyfruj'; err.textContent='Złe hasło albo uszkodzony plik.';});
}

function plN(n,one,few,many){var m10=n%10,m100=n%100; if(n===1) return one; if(m10>=2&&m10<=4&&!(m100>=12&&m100<=14)) return few; return many;}
/* ---------- Ustawienia ---------- */
function medRowHTML(m,t){
  var p=phaseAt(m,t)||m.phases[0], sub='';
  if(m.phases[0].from>t) sub='od '+plDate(m.phases[0].from)+': ';
  sub+=phaseText(m,p);
  if(m.end) sub+='. '+(m.end<t?'Zakończony ':'Do ')+plDate(m.end);
  return '<button type="button" class="srow" data-act="medEdit" data-id="'+esc(m.id)+'"><div><div class="srow-t">'+esc(m.name)+'</div><div class="srow-s">'+esc(sub)+'</div></div><i class="ph ph-caret-right" aria-hidden="true"></i></button>';
}
function renderSettings(){
  var t=todayIso(), c=S.settings;
  var active=S.meds.filter(function(m){return !m.end||m.end>=t;}), ended=S.meds.filter(function(m){return m.end&&m.end<t;});
  var evs=S.events.slice().sort(function(a,b){
    var fa=a.date>=t, fb=b.date>=t;
    if(fa!==fb) return fa?-1:1;
    return fa?(a.date<b.date?-1:1):(a.date<b.date?1:-1);
  });
  var h='';
  h+='<div class="sec"><div class="sec-h"><h2>Leki</h2><button class="ghost" type="button" data-act="medNew"><i class="ph-bold ph-plus" aria-hidden="true"></i>Dodaj lek</button></div>'
    +(active.length?'<div class="list">'+active.map(function(m){return medRowHTML(m,t);}).join('')+'</div>':'<div class="empty">Brak aktywnych leków.</div>')
    +(ended.length?'<details class="ended"><summary>Zakończone ('+ended.length+')</summary><div class="list">'+ended.map(function(m){return medRowHTML(m,t);}).join('')+'</div></details>':'')
    +'</div>';

  h+='<div class="sec"><div class="sec-h"><h2>Pory dnia</h2><button class="ghost" type="button" data-act="slotNew"><i class="ph-bold ph-plus" aria-hidden="true"></i>Dodaj porę</button></div><div class="list">'
    +sortedSlots().map(function(sl){return '<button type="button" class="srow" data-act="slotEdit" data-id="'+esc(sl.id)+'"><div><div class="srow-t">'+esc(sl.name)+'</div><div class="srow-s mono">'+esc(sl.time)+'</div></div><i class="ph ph-caret-right" aria-hidden="true"></i></button>';}).join('')
    +'</div></div>';

  h+='<div class="sec"><div class="sec-h"><h2>Kontrole i przypomnienia</h2><button class="ghost" type="button" data-act="evNew"><i class="ph-bold ph-plus" aria-hidden="true"></i>Dodaj</button></div>'
    +(evs.length?'<div class="list">'+evs.map(function(e){return '<button type="button" class="srow" data-act="evEdit" data-id="'+esc(e.id)+'"><div><div class="srow-t">'+esc(e.title)+'</div><div class="srow-s">'+plDate(e.date)+(e.field?', pole: '+esc(e.field):'')+'</div></div><i class="ph ph-caret-right" aria-hidden="true"></i></button>';}).join('')+'</div>'
      :'<div class="empty">Brak kontroli. Dodaj np. badanie krwi lub wizytę u lekarza, a pojawi się w danym dniu.</div>')
    +'</div>';

  h+='<div class="sec"><div class="sec-h"><h2>Parametry badań</h2><button class="ghost" type="button" data-act="mkNew"><i class="ph-bold ph-plus" aria-hidden="true"></i>Dodaj</button></div>'+(S.markers.length>8?'<details class="ended"><summary>Pokaż wszystkie ('+S.markers.length+')</summary>':'')+'<div class="list">'
    +S.markers.map(function(m){return '<button type="button" class="srow" data-act="mkEdit" data-id="'+esc(m.id)+'"><div><div class="srow-t">'+esc(m.name)+'</div><div class="srow-s">'+esc(m.unit)+(rangeText(m)?', zakres odniesienia '+esc(rangeText(m)):', bez zakresu')+'</div></div><i class="ph ph-caret-right" aria-hidden="true"></i></button>';}).join('')
    +'</div>'+(S.markers.length>8?'</details>':'')+'<p class="hint">Zakresy są orientacyjne. Wpisz te z wyników swojego laboratorium.</p></div>';

  h+='<div class="sec"><div class="sec-h"><h2>Ogólne</h2></div><div class="formcard stack">'
    +'<div><label class="f" for="sPatient">Imię i nazwisko (do raportu)</label><input class="in" id="sPatient" data-set="patient" value="'+esc(S.patient)+'" autocomplete="off"></div>'
    +'<div class="grid2"><div><label class="f" for="sGoal">Cel wody, szklanek (0 = wyłączone)</label><input class="in mono" id="sGoal" data-set="waterGoal" inputmode="numeric" value="'+c.waterGoal+'"></div>'
    +'<div><label class="f" for="sGlass">Szklanka, ml</label><input class="in mono" id="sGlass" data-set="glassMl" inputmode="numeric" value="'+c.glassMl+'"></div></div>'
    +'<div><label class="f" for="sLate">Dawka spóźniona po (min od pory, 0 = wyłączone)</label><input class="in mono" id="sLate" data-set="lateMin" inputmode="numeric" value="'+c.lateMin+'"></div>'
    +'<div><span class="f">Oznaczaj ciśnienie jako podwyższone od</span><div class="grid2"><input class="in mono" id="sHiS" data-set="hiSys" inputmode="numeric" value="'+c.hiSys+'" aria-label="Skurczowe, próg podwyższone"><input class="in mono" id="sHiD" data-set="hiDia" inputmode="numeric" value="'+c.hiDia+'" aria-label="Rozkurczowe, próg podwyższone"></div></div>'
    +'<div><span class="f">Oznaczaj ciśnienie jako niskie poniżej</span><div class="grid2"><input class="in mono" id="sLoS" data-set="loSys" inputmode="numeric" value="'+c.loSys+'" aria-label="Skurczowe, próg niskie"><input class="in mono" id="sLoD" data-set="loDia" inputmode="numeric" value="'+c.loDia+'" aria-label="Rozkurczowe, próg niskie"></div><p class="hint">Progi ustaw według zaleceń lekarza. Aplikacja tylko oznacza wartości.</p></div>'
    +'<div><span class="f" id="themeL">Motyw</span><div class="seg" role="group" aria-labelledby="themeL">'
      +[['auto','Auto'],['light','Jasny'],['dark','Ciemny']].map(function(x){return '<button type="button" data-act="theme" data-t="'+x[0]+'" aria-pressed="'+(c.theme===x[0])+'">'+x[1]+'</button>';}).join('')+'</div></div>'
    +'<div><label class="f" for="sNote">Dopisek na raporcie</label><textarea class="in" id="sNote" data-set="reportNote" placeholder="np. rozpoznanie, cel kuracji">'+esc(c.reportNote)+'</textarea></div>'
    +'<div><label class="f" for="sRules">Zasady i notatki (przycisk Zasady u góry)</label><textarea class="in" id="sRules" data-set="rules" rows="6">'+esc(S.rules)+'</textarea></div>'
    +'</div></div>';

  h+='<div class="sec"><div class="sec-h"><h2>Przypomnienia o lekach</h2></div><div class="formcard stack">'
    +(isNative()
      ?'<p class="muted" style="margin:0">Powiadomienie o porach dawek z Twojego planu, także przy zamkniętej aplikacji. Pomija pory, które są już odhaczone.</p>'
       +'<button class="btn'+(S.settings.remind.on?'':' pri')+'" type="button" data-act="remindToggle" aria-pressed="'+S.settings.remind.on+'">'+(S.settings.remind.on?'Wyłącz przypomnienia':'Włącz przypomnienia')+'</button>'
       +'<div><label class="f" for="sLead">Ile minut przed porą dawki (0 = równo o porze)</label><input class="in mono" id="sLead" data-set="remindLead" inputmode="numeric" value="'+S.settings.remind.lead+'"></div>'
       +'<button class="linkbtn" type="button" data-act="remindTest" style="justify-self:start">Wyślij testowe powiadomienie</button>'
       +'<p class="hint" id="remInfo">'+esc(remInfo)+'</p>'
      :'<p class="muted" style="margin:0">Przypomnienia działają w aplikacji na Androida (APK). W przeglądarce ta funkcja jest niedostępna.</p>')
    +'</div></div>';

  var bk=S.lastBackup?plDate(S.lastBackup):'jeszcze nie zrobiono';
  h+='<div class="sec"><div class="sec-h"><h2>Dane</h2></div><div class="formcard stack">'
    +'<p class="muted" style="margin:0">Ostatnia kopia: <b>'+bk+'</b>. Dane są zapisane tylko na tym urządzeniu, w tej aplikacji. Odinstalowanie aplikacji albo wyczyszczenie jej danych usunie je, dopóki nie wczytasz kopii.</p>'
    +(location.protocol==='file:'?'<div class="alert" style="margin-top:0"><i class="ph-fill ph-warning" aria-hidden="true"></i><div><b>Plik otwarty lokalnie</b><p>Część przeglądarek, zwłaszcza na telefonie, nie zachowuje danych plików otwartych z pamięci. Wgraj plik na stały adres (np. swój hosting) albo rób kopie JSON często.</p></div></div>':'')
    +'<button class="btn pri wide" type="button" data-act="encExport"><i class="ph ph-lock-key" aria-hidden="true"></i>Zaszyfrowana kopia (hasło)</button>'
    +'<p class="hint" style="margin:-6px 0 0">Zalecane do chmury (Dysk Google, Filen). Dane są szyfrowane na tym urządzeniu, zanim opuszczą telefon.</p>'
    +'<div class="actions two"><button class="btn" type="button" data-act="export"><i class="ph ph-download-simple" aria-hidden="true"></i>Pobierz JSON</button><button class="btn" type="button" data-act="copy"><i class="ph ph-copy" aria-hidden="true"></i>Kopiuj JSON</button></div>'
    +'<button class="btn wide" type="button" data-act="import"><i class="ph ph-upload-simple" aria-hidden="true"></i>Wczytaj kopię z pliku</button>'
    +'<button class="linkbtn danger" type="button" data-act="askClear" style="justify-self:start">Usuń wszystkie dane</button>'
    +'</div></div>';
  $('settingsBody').innerHTML=h;
}
function applySetting(t){
  var k=t.dataset.set, v=t.value;
  if(k==='patient') S.patient=v.trim();
  else if(k==='rules') S.rules=v;
  else if(k==='reportNote') S.settings.reportNote=v;
  else if(k==='remindLead'){var ld=Math.round(numOr(v,0,0,120)); S.settings.remind.lead=ld; t.value=ld;}
  else{
    var lim={lateMin:[0,600,60],waterGoal:[0,20,10],glassMl:[50,1000,250],hiSys:[100,250,135],hiDia:[50,150,85],loSys:[60,130,100],loDia:[30,100,60]}[k];
    if(!lim) return;
    var n=Math.round(numOr(v,lim[2],lim[0],lim[1])); S.settings[k]=n; t.value=n;
  }
  save();
}
function applyTheme(){
  var th=S.settings.theme, r=document.documentElement;
  if(th==='light'||th==='dark') r.setAttribute('data-theme',th); else r.removeAttribute('data-theme');
}

/* ---------- Edytor leku ---------- */
var ed=null;
function openMed(id){
  var t=todayIso(), m=id?medById(id):null;
  if(m){
    var p=phaseAt(m,t)||m.phases[0];
    ed={id:m.id,name:m.name,substance:m.substance,note:m.note,unit:m.unit,timerMin:String(m.timerMin||0),mmol:m.mmol?num(m.mmol):'',end:m.end||'',
      from:m.phases[0].from>t?m.phases[0].from:t,doses:Object.assign({},p.doses),dow:p.dow.slice(),replaces:null};
  }else{
    ed={id:null,name:'',substance:'',note:'',unit:'tabl.',timerMin:'0',mmol:'',end:'',from:t,doses:{},dow:ALL_DOW.slice(),replaces:null};
  }
  renderMedForm(); $('medDlg').showModal();
}
function openReplace(oldId){
  var old=medById(oldId); if(!old) return;
  var t=todayIso(), p=phaseAt(old,t)||old.phases[0];
  ed={id:null,name:'',substance:'',note:'',unit:old.unit,timerMin:'0',mmol:'',end:'',from:t,doses:Object.assign({},p.doses),dow:p.dow.slice(),replaces:oldId};
  renderMedForm(); if(!$('medDlg').open) $('medDlg').showModal();
}
function renderMedForm(){
  var isNew=!ed.id, m=isNew?null:medById(ed.id), t=todayIso();
  var oldMed=ed.replaces?medById(ed.replaces):null;
  var h='<h3>'+(oldMed?'Nowy lek w miejsce: '+esc(oldMed.name):(isNew?'Nowy lek':'Edycja leku'))+'</h3><div class="stack">';
  if(oldMed) h+='<p class="muted" style="margin:0">Po zapisaniu '+esc(oldMed.name)+' zostanie zakończony w dniu przed datą początku nowego leku. Jego historia i raporty zostają.</p>';
  if(!isNew){
    var y=addDays(t,-1);
    if(m.end) h+='<div class="fs"><div class="fs-h">Lek zakończony</div><p class="muted" style="margin:0">Ostatni dzień przyjmowania: '+plDate(m.end)+'. Od następnego dnia lek nie pojawia się w planie, a historia zostaje.</p><button class="btn" type="button" data-act="resume">Wznów lek</button></div>';
    else h+='<div class="fs"><div class="fs-h">Zakończyć przyjmowanie tego leku?</div><p class="muted" style="margin:0">Zakończenie nie usuwa historii. Wybierz ostatni dzień przyjmowania.</p>'
      +'<div class="grid2"><button class="btn col" type="button" data-act="endAt" data-iso="'+y+'"><span>Do wczoraj</span><small class="mono">'+shortDate(y)+'</small></button><button class="btn col" type="button" data-act="endAt" data-iso="'+t+'"><span>Do dziś</span><small class="mono">'+shortDate(t)+'</small></button></div>'
      +'<button class="btn wide" type="button" data-act="medReplace">Zamień na inny lek</button></div>';
  }
  h+='<div><label class="f" for="edName">Nazwa</label><input class="in" id="edName" data-ed="name" value="'+esc(ed.name)+'" autocomplete="off" placeholder="np. nazwa z opakowania"></div>'
    +'<div><label class="f" for="edSub">Substancja (opcjonalnie)</label><input class="in" id="edSub" data-ed="substance" value="'+esc(ed.substance)+'" autocomplete="off" placeholder="np. substancja czynna"></div>'
    +'<div><label class="f" for="edNote">Wskazówka przy dawce (opcjonalnie)</label><input class="in" id="edNote" data-ed="note" value="'+esc(ed.note)+'" autocomplete="off" placeholder="np. po posiłku, popić wodą"></div>'
    +'<div class="fs"><div class="fs-h">Dawkowanie</div>'
    +'<div><label class="f" for="edFrom">Obowiązuje od</label><input class="in mono" type="date" id="edFrom" data-ed="from" value="'+esc(ed.from)+'"><p class="hint">'
      +(isNew?'Pierwszy dzień przyjmowania.':'Nowe dawkowanie zaczyna obowiązywać od tego dnia. Wcześniejsze dni zostają bez zmian.')+'</p></div>'
    +'<div class="stack" style="gap:10px">'+sortedSlots().map(function(sl){
      var v=ed.doses[sl.id]||0;
      return '<div class="doserow"><div><b>'+esc(sl.name)+'</b><span>'+esc(sl.time)+'</span></div>'
        +'<div class="stepper"><button type="button" data-act="doseMinus" data-slot="'+esc(sl.id)+'" aria-label="Mniej: '+esc(sl.name)+'"><span aria-hidden="true">−</span></button>'
        +'<output class="mono" data-out="'+esc(sl.id)+'">'+num(v)+'</output>'
        +'<button type="button" data-act="dosePlus" data-slot="'+esc(sl.id)+'" aria-label="Więcej: '+esc(sl.name)+'"><span aria-hidden="true">+</span></button></div></div>';
    }).join('')+'<p class="hint" style="margin:0">Dawka w '+(esc(ed.unit)||'tabl.')+' na jedno przyjęcie. 0 oznacza, że w tej porze leku nie bierzesz.</p></div>'
    +'<div><span class="f" id="dowL">Dni tygodnia</span><div class="dow" role="group" aria-labelledby="dowL">'
      +DOW_ORDER.map(function(d){return '<button type="button" data-act="dow" data-d="'+d+'" aria-pressed="'+(ed.dow.indexOf(d)>=0)+'">'+DOW[d]+'</button>';}).join('')+'</div></div>'
    +'</div>';
  if(!isNew){
    var cur=phaseAt(m,t);
    h+='<div class="hist"><div class="hist-h">Historia dawkowania</div>'+m.phases.map(function(p){
      return '<div class="hrow"><div><b>od '+plDate(p.from)+(p===cur?' (obecne)':'')+'</b><div>'+esc(phaseText(m,p))+'</div></div>'
        +(m.phases.length>1?'<button type="button" class="tbtn" data-act="phaseDel" data-from="'+p.from+'" aria-label="Usuń zmianę od '+plDate(p.from)+'">Usuń</button>':'')+'</div>';
    }).join('')+'</div>';
  }
  h+='<div class="grid2"><div><label class="f" for="edUnit">Jednostka</label><input class="in" id="edUnit" data-ed="unit" value="'+esc(ed.unit)+'" autocomplete="off"></div>'
    +'<div><label class="f" for="edTimer">Stoper po dawce, min</label><input class="in mono" id="edTimer" data-ed="timerMin" inputmode="numeric" value="'+esc(ed.timerMin)+'"></div></div>'
    +'<p class="hint" style="margin:-6px 0 0">Stoper (0 = bez) odlicza czas od odhaczenia dawki, np. 30 minut, w czasie których nie wolno się kłaść.</p>'
    +'<div><label class="f" for="edMmol">Potas w jednej tabletce, mmol (opcjonalnie)</label><input class="in mono" id="edMmol" data-ed="mmol" inputmode="decimal" value="'+esc(ed.mmol)+'" placeholder="np. 10"><p class="hint">Jeśli podasz, dzień pokaże sumę potasu z leków.</p></div>'
    +'<div><label class="f" for="edEnd">Ostatni dzień przyjmowania (opcjonalnie)</label><input class="in mono" type="date" id="edEnd" data-ed="end" value="'+esc(ed.end)+'">'
      +'<p class="hint">Po tym dniu lek znika z listy dnia i trafia do zakończonych. Historia zostaje.'+(ed.end?' <button type="button" class="linkbtn" data-act="endClear" style="padding:0">Usuń datę końca</button>':'')+'</p></div>'
    +'<p class="muted" style="margin:0">Dawkowanie zmieniaj zgodnie z zaleceniem lekarza.</p>'
    +'<div class="err" id="medErr" role="alert"></div>'
    +'<div class="grid2"><button class="btn" type="button" data-act="close">Anuluj</button><button class="btn pri" type="button" data-act="medSave">Zapisz</button></div>'
    +(isNew?'':'<button class="linkbtn danger" type="button" data-act="medDelete" style="justify-self:start">Usuń lek razem z historią</button>')
    +'</div>';
  $('medBody').innerHTML=h;
}
function medErr(msg){var e=$('medErr'); if(e) e.textContent=msg;}
function medSave(msg){
  var isNew=!ed.id, m=isNew?null:medById(ed.id);
  var name=String(ed.name||'').trim();
  if(!name){medErr('Wpisz nazwę leku.');return;}
  if(!isIso(ed.from)){medErr('Wybierz datę początku dawkowania.');return;}
  var doses={}, any=false;
  Object.keys(ed.doses).forEach(function(k){var v=ed.doses[k]; if(v>0&&slotById(k)){doses[k]=v; any=true;}});
  if(!any){medErr('Ustaw dawkę w przynajmniej jednej porze dnia.');return;}
  if(!ed.dow.length){medErr('Wybierz przynajmniej jeden dzień tygodnia.');return;}
  var first=isNew?ed.from:(m.phases[0].from<ed.from?m.phases[0].from:ed.from);
  if(ed.end){
    if(!isIso(ed.end)){medErr('Data końca jest nieprawidłowa.');return;}
    if(ed.end<first){medErr('Ostatni dzień przyjmowania nie może być przed początkiem dawkowania.');return;}
  }
  if(isNew){m={id:uid(),phases:[]};}
  m.name=name; m.substance=String(ed.substance||'').trim(); m.note=String(ed.note||'').trim();
  m.unit=String(ed.unit||'').trim()||'tabl.';
  m.timerMin=Math.round(numOr(ed.timerMin,0,0,600)); m.mmol=numOr(ed.mmol,0,0,1000); m.end=ed.end||null;
  upsertPhase(m,ed.from,doses,ed.dow.slice().sort(function(a,b){return a-b;}));
  if(isNew) S.meds.push(m);
  var note=isNew?'Dodano lek '+name:'Zapisano zmiany: '+name;
  if(ed.replaces){
    var old=medById(ed.replaces), last=addDays(ed.from,-1);
    if(old&&(!old.end||old.end>last)) old.end=last;
    if(old) note='Dodano '+name+'. '+old.name+' zakończony '+plDate(last)+'.';
  }
  save(); $('medDlg').close(); renderAll(); toast(typeof msg==='string'?msg:note);
}
function doseStep(slot,delta){
  var v=Math.round(((ed.doses[slot]||0)+delta)*2)/2;
  v=Math.max(0,Math.min(20,v));
  if(v>0) ed.doses[slot]=v; else delete ed.doses[slot];
  var o=document.querySelector('#medDlg [data-out="'+slot+'"]'); if(o) o.textContent=num(v);
}

/* ---------- Edytor pory dnia ---------- */
var eslot=null;
function openSlot(id){
  var s=id?slotById(id):null;
  eslot=s?{id:s.id,name:s.name,time:s.time}:{id:null,name:'',time:'12:00'};
  var h='<h3>'+(s?'Edycja pory dnia':'Nowa pora dnia')+'</h3><div class="stack">'
    +'<div><label class="f" for="esName">Nazwa</label><input class="in" id="esName" data-es="name" value="'+esc(eslot.name)+'" autocomplete="off" placeholder="np. Podwieczorek"></div>'
    +'<div><label class="f" for="esTime">Godzina</label><input class="in mono" type="time" id="esTime" data-es="time" value="'+esc(eslot.time)+'"><p class="hint">Godzina ustala kolejność pór na liście dnia.</p></div>'
    +'<div class="err" id="slotErr" role="alert"></div>'
    +'<div class="grid2"><button class="btn" type="button" data-act="close">Anuluj</button><button class="btn pri" type="button" data-act="slotSave">Zapisz</button></div>'
    +(s?'<button class="linkbtn danger" type="button" data-act="slotDelete" style="justify-self:start">Usuń porę dnia</button>':'')+'</div>';
  $('slotBody').innerHTML=h; $('slotDlg').showModal();
}
function slotSave(){
  var name=String(eslot.name||'').trim();
  if(!name){$('slotErr').textContent='Wpisz nazwę pory dnia.';return;}
  if(!isTime(eslot.time)){$('slotErr').textContent='Wybierz godzinę.';return;}
  if(eslot.id){var s=slotById(eslot.id); s.name=name; s.time=eslot.time;}
  else S.slots.push({id:uid(),name:name,time:eslot.time});
  save(); $('slotDlg').close(); renderAll(); toast('Zapisano porę dnia');
}
function slotDelete(){
  if(S.slots.length<=1){$('slotErr').textContent='Musi zostać przynajmniej jedna pora dnia.';return;}
  var id=eslot.id, used=S.meds.filter(function(m){return m.phases.some(function(p){return p.doses[id]>0;});}).length;
  confirmBox('Usunąć porę dnia?',(used?'Pora jest używana przez '+used+' '+(used===1?'lek':'leków')+'. Ich dawki w tej porze znikną z planu. ':'')+'Wcześniejsze odhaczenia zostają w danych.','Usuń',true,function(){
    S.slots=S.slots.filter(function(s){return s.id!==id;});
    S.meds.forEach(function(m){m.phases.forEach(function(p){delete p.doses[id];});});
    save(); $('slotDlg').close(); renderAll(); toast('Usunięto porę dnia');
  });
}

/* ---------- Edytor kontroli ---------- */
var eev=null;
function openEv(id){
  var e=null; S.events.forEach(function(x){if(x.id===id) e=x;});
  eev=e?{id:e.id,date:e.date,title:e.title,note:e.note,field:e.field,marker:e.marker||''}:{id:null,date:todayIso(),title:'',note:'',field:'',marker:''};
  var h='<h3>'+(e?'Edycja kontroli':'Nowa kontrola')+'</h3><div class="stack">'
    +'<div><label class="f" for="eeTitle">Nazwa</label><input class="in" id="eeTitle" data-ee="title" value="'+esc(eev.title)+'" autocomplete="off" placeholder="np. Wizyta u kardiologa"></div>'
    +'<div><label class="f" for="eeDate">Data</label><input class="in mono" type="date" id="eeDate" data-ee="date" value="'+esc(eev.date)+'"></div>'
    +'<div><label class="f" for="eeNote">Treść przypomnienia (opcjonalnie)</label><textarea class="in" id="eeNote" data-ee="note" placeholder="np. na czczo, zabierz wyniki">'+esc(eev.note)+'</textarea></div>'
    +'<div><label class="f" for="eeMarker">Parametr badania (opcjonalnie)</label><select class="in" id="eeMarker" data-ee="marker"><option value="">Brak, tylko pole tekstowe</option>'+S.markers.map(function(m){return '<option value="'+esc(m.id)+'"'+(m.id===eev.marker?' selected':'')+'>'+esc(m.name+(m.unit?' ('+m.unit+')':''))+'</option>';}).join('')+'</select><p class="hint">Wynik wpisany w dniu kontroli trafi do dziennika w zakładce Badania.</p></div>'
    +'<div><label class="f" for="eeField">Pole na wynik (opcjonalnie)</label><input class="in" id="eeField" data-ee="field" value="'+esc(eev.field)+'" autocomplete="off" placeholder="np. Wynik (jednostka)"><p class="hint">W dniu kontroli pojawi się pole do wpisania wyniku.</p></div>'
    +'<div class="err" id="evErr" role="alert"></div>'
    +'<div class="grid2"><button class="btn" type="button" data-act="close">Anuluj</button><button class="btn pri" type="button" data-act="evSave">Zapisz</button></div>'
    +(e?'<button class="linkbtn danger" type="button" data-act="evDelete" style="justify-self:start">Usuń kontrolę</button>':'')+'</div>';
  $('evBody').innerHTML=h; $('evDlg').showModal();
}
function evSave(){
  var title=String(eev.title||'').trim();
  if(!title){$('evErr').textContent='Wpisz nazwę kontroli.';return;}
  if(!isIso(eev.date)){$('evErr').textContent='Wybierz datę.';return;}
  var rec={id:eev.id||uid(),date:eev.date,title:title,note:String(eev.note||'').trim(),field:String(eev.field||'').trim(),marker:markerById(eev.marker)?eev.marker:''};
  if(eev.id){S.events=S.events.map(function(x){return x.id===eev.id?rec:x;});}
  else S.events.push(rec);
  syncEventLab(rec.id);
  save(); $('evDlg').close(); renderAll(); toast('Zapisano kontrolę');
}

/* ---------- Dialogi pomocnicze ---------- */
var pendingConfirm=null;
function confirmBox(title,text,ok,danger,fn){
  pendingConfirm=fn;
  $('confirmBody').innerHTML='<h3>'+esc(title)+'</h3><p>'+esc(text)+'</p><div class="grid2"><button class="btn" type="button" data-act="close">Anuluj</button><button class="btn '+(danger?'danger':'pri')+'" type="button" data-act="confirmOk">'+esc(ok)+'</button></div>';
  $('confirmDlg').showModal();
}
function openRules(){
  $('rulesBody').innerHTML='<h3>Zasady i notatki</h3>'+(S.rules.trim()?'<div class="rules-t">'+esc(S.rules)+'</div>':'<p>Nie ma jeszcze notatek. Dopisz je w Ustawieniach, w sekcji Ogólne.</p>')+'<button class="btn wide" type="button" data-act="close">Zamknij</button>';
  $('rulesDlg').showModal();
}

/* ---------- Widoki i zdarzenia ---------- */
var toastT=null;
function toast(msg){
  var t=$('toast'); t.textContent=msg; t.hidden=false;
  clearTimeout(toastT); toastT=setTimeout(function(){t.hidden=true;},2800);
}
function setView(v){
  view=v;
  ['meds','bp','labs','report','settings'].forEach(function(n){$('view-'+n).hidden=n!==v;});
  Array.prototype.forEach.call(document.querySelectorAll('.tab'),function(b){b.setAttribute('aria-selected',String(b.dataset.v===v));});
  if(v==='meds'){renderMeds(); loadDayInputs();}
  if(v==='bp'){resetBpForm(false); renderBP();}
  if(v==='labs') renderLabs();
  if(v==='report') renderReport();
  if(v==='settings') renderSettings();
  window.scrollTo(0,0);
}
function renderAll(){
  renderMeds(); loadDayInputs();
  if(view==='bp') renderBP();
  if(view==='labs') renderLabs();
  if(view==='report') renderReport();
  if(view==='settings') renderSettings();
}
function closeDlg(el){var d=el.closest('dialog'); if(d) d.close();}

document.addEventListener('click',function(e){
  var row=e.target.closest('.med');
  if(row&&!e.target.closest('.tm,.skip')){toggleKey(row.dataset.key); return;}
  var el=e.target.closest('[data-act]'); if(!el) return;
  var d=el.dataset;
  switch(d.act){
    case 'tab': setView(d.v); break;
    case 'encExport': openEnc(); break;
    case 'encGo': encGo(); break;
    case 'encSave': encSave(); break;
    case 'encShare': encShare(); break;
    case 'decGo': decGo(); break;
    case 'skip': openSkip(d.key); break;
    case 'skReason':
      skEd.reason=(skEd.reason===d.r)?'':d.r;
      Array.prototype.forEach.call($('skPills').querySelectorAll('.pill'),function(b){b.setAttribute('aria-pressed',String(b.dataset.r===skEd.reason));}); break;
    case 'skSave': skSave(); break;
    case 'skClear': skClear(); break;
    case 'labOpen': labOpen=d.id; labSel=d.id; renderLabs(); window.scrollTo(0,0); break;
    case 'labBack': labOpen=''; renderLabs(); window.scrollTo(0,0); break;
    case 'labPrev': labNav(-1); break;
    case 'labNext': labNav(1); break;
    case 'labMode': labView=d.v; renderLabs(); break;
    case 'labNew': openLab(null,labOpen||labSel); break;
    case 'labEdit': openLab(d.id); break;
    case 'labSave': labSave(); break;
    case 'labDelete': labDelete(); break;
    case 'mkNew': openMk(null); break;
    case 'mkEdit': openMk(d.id); break;
    case 'mkSave': mkSave(); break;
    case 'mkDelete': mkDelete(); break;
    case 'goData': setView('settings'); break;
    case 'goMeds': setView('settings'); openMed(null); break;
    case 'day': sel=d.iso; renderMeds(); loadDayInputs(); break;
    case 'today': sel=todayIso(); renderMeds(); loadDayInputs(); break;
    case 'all': markAll(); break;
    case 'water':
      if(sel>todayIso()){toast('Nie można zapisywać wody z przyszłości.'); break;}
      var n=+d.n, r=dayRec(sel); r.water=(r.water===n)?n-1:n; save(); renderMeds(); break;
    case 'rules': openRules(); break;
    case 'askReset':
      confirmBox('Wyczyścić ten dzień?','Odhaczone leki, woda, notatka i wyniki kontroli z dnia '+plDate(sel)+' zostaną usunięte. Pomiary ciśnienia zostają.','Wyczyść',true,function(){delete S.days[sel]; save(); renderMeds(); loadDayInputs(); toast('Wyczyszczono wybrany dzień');}); break;
    case 'askClear':
      confirmBox('Usunąć wszystkie dane?','Znikną leki, dawkowanie, odhaczenia, pomiary ciśnienia, wyniki badań i ustawienia z tego urządzenia. Tej operacji nie da się cofnąć, chyba że masz kopię JSON lub zaszyfrowaną kopię.','Usuń wszystko',true,function(){
        S=freshState(); labOpen=''; labSel=S.markers[0].id; save(); applyTheme(); sel=todayIso(); rangeFrom=''; rangeTo=''; tickTimer(true); renderAll(); toast('Usunięto wszystkie dane');}); break;
    case 'confirmOk': var fn=pendingConfirm; pendingConfirm=null; $('confirmDlg').close(); if(fn) fn(); break;
    case 'close': closeDlg(el); break;
    case 'ctx': setCtx(d.ctx); break;
    case 'bpRange': bpRange=+d.r; renderBP(); break;
    case 'bpdel': pendingDel=d.id; renderBP(); break;
    case 'bpdelno': pendingDel=null; renderBP(); break;
    case 'bpdelok': S.bp=S.bp.filter(function(r){return r.id!==d.id;}); pendingDel=null; save(); renderBP(); toast('Usunięto pomiar'); break;
    case 'rpPreset': rpPreset=+d.p; rangeFrom=''; rangeTo=''; renderReport(); break;
    case 'print': renderReport(); doPrint(); break;
    case 'aiCopy': aiCopy(); break;
    case 'aiSave': aiSave(); break;
    case 'export': exportJSON(); break;
    case 'remindToggle': remindToggle(); break;
    case 'remindTest': remindTest(); break;
    case 'copy': copyJSON(); break;
    case 'import': $('impFile').click(); break;
    case 'theme': S.settings.theme=d.t; save(); applyTheme(); renderSettings(); break;
    case 'hideTimer': S.timerEnd=0; save(); tickTimer(true); break;
    case 'medNew': openMed(null); break;
    case 'medEdit': openMed(d.id); break;
    case 'dosePlus': doseStep(d.slot,0.5); break;
    case 'doseMinus': doseStep(d.slot,-0.5); break;
    case 'dow':
      var wd=+d.d, ix=ed.dow.indexOf(wd);
      if(ix>=0) ed.dow.splice(ix,1); else ed.dow.push(wd);
      el.setAttribute('aria-pressed',String(ed.dow.indexOf(wd)>=0)); break;
    case 'endClear': ed.end=''; renderMedForm(); break;
    case 'phaseDel':
      var mm=medById(ed.id);
      if(mm&&mm.phases.length>1){mm.phases=mm.phases.filter(function(p){return p.from!==d.from;}); save(); renderMedForm(); renderAll(); toast('Usunięto zmianę dawkowania');}
      break;
    case 'medSave': medSave(); break;
    case 'medReplace': openReplace(ed.id); break;
    case 'endAt': ed.end=d.iso; medSave('Zakończono '+ed.name+'. Ostatni dzień: '+plDate(d.iso)+'.'); break;
    case 'resume': ed.end=''; medSave('Wznowiono lek '+ed.name+'.'); break;
    case 'medDelete':
      var md=medById(ed.id); if(!md) break;
      confirmBox('Usunąć lek '+md.name+'?','Lek zniknie z planu, a odhaczenia tego leku zostaną usunięte z historii. Jeśli chcesz tylko przestać go brać, zamiast tego ustaw ostatni dzień przyjmowania.','Usuń lek',true,function(){
        var id=md.id;
        S.meds=S.meds.filter(function(x){return x.id!==id;});
        Object.keys(S.days).forEach(function(k){var r=S.days[k]; Object.keys(r.taken).forEach(function(key){if(key.indexOf(id+'|')===0){delete r.taken[key]; delete r.times[key];}});});
        save(); $('medDlg').close(); renderAll(); toast('Usunięto lek');
      }); break;
    case 'slotNew': openSlot(null); break;
    case 'slotEdit': openSlot(d.id); break;
    case 'slotSave': slotSave(); break;
    case 'slotDelete': slotDelete(); break;
    case 'evNew': openEv(null); break;
    case 'evEdit': openEv(d.id); break;
    case 'evSave': evSave(); break;
    case 'evDelete':
      var eid=eev.id;
      confirmBox('Usunąć kontrolę?','Zniknie przypomnienie. Wpisane wcześniej wyniki zostają w danych dnia.','Usuń',true,function(){S.events=S.events.filter(function(x){return x.id!==eid;}); save(); $('evDlg').close(); renderAll(); toast('Usunięto kontrolę');}); break;
  }
});
['rulesDlg','confirmDlg'].forEach(function(id){
  $(id).addEventListener('click',function(e){if(e.target===this) this.close();});
});
document.addEventListener('input',function(e){
  var t=e.target, ds=t.dataset||{};
  if(t.id==='dayNote'){dayRec(sel).note=t.value; save();}
  else if(ds.aictx!==undefined){S.settings.ai.context=t.value.slice(0,2000); save(); refreshAi();}
  else if(ds.ev){dayRec(sel).results[ds.ev]=t.value.trim(); syncEventLab(ds.ev); save();}
  else if(ds.sk!==undefined) skEd[ds.sk]=t.value;
  else if(ds.lb!==undefined) labEd[ds.lb]=t.value;
  else if(ds.mk!==undefined) mkEd[ds.mk]=t.value;
  else if(ds.ed!==undefined) ed[ds.ed]=t.value;
  else if(ds.es!==undefined) eslot[ds.es]=t.value;
  else if(ds.ee!==undefined) eev[ds.ee]=t.value;
});
document.addEventListener('change',function(e){
  var t=e.target, ds=t.dataset||{};
  if(t.classList.contains('tm')){dayRec(sel).times[ds.key]=t.value||''; save();}
  else if(ds.rp){S.settings.rp[ds.rp]=t.checked; save(); renderReport();}
  else if(ds.ai){S.settings.ai[ds.ai]=t.checked; save(); refreshAi();}
  else if(t.id==='jumpDate'){if(isIso(t.value)){sel=t.value; renderMeds(); loadDayInputs();}}
  else if(ds.set) applySetting(t);
  else if(ds.ed!==undefined) ed[ds.ed]=t.value;
  else if(ds.es!==undefined) eslot[ds.es]=t.value;
  else if(ds.lb!==undefined){labEd[ds.lb]=t.value; if(ds.lb==='marker'){var mm2=markerById(t.value); $('lbUnit').textContent=mm2?mm2.unit:'';}}
  else if(ds.mk!==undefined) mkEd[ds.mk]=t.value;
  else if(ds.ee!==undefined){eev[ds.ee]=t.value; if(ds.ee==='marker'){var mm3=markerById(t.value); if(mm3&&!String(eev.field||'').trim()){eev.field=mm3.name+(mm3.unit?' ('+mm3.unit+')':''); $('eeField').value=eev.field;}}}
  else if(t.id==='rpFrom'){rangeFrom=t.value; renderReport();}
  else if(t.id==='rpTo'){rangeTo=t.value; renderReport();}
});
$('bpForm').addEventListener('submit',submitBP);
$('impFile').addEventListener('change',function(){if(this.files&&this.files[0]) importJSON(this.files[0]); this.value='';});
document.addEventListener('visibilitychange',function(){if(!document.hidden){queueReminders(); tickTimer(true); if(view==='meds') renderMeds();}});
setInterval(function(){
  if(document.hidden||view!=='meds'||sel!==todayIso()||document.querySelector('dialog[open]')) return;
  var a=document.activeElement; if(a&&/^(INPUT|TEXTAREA|SELECT)$/.test(a.tagName)) return;
  renderMeds();
},60000);

/* Wydruk z menu przeglądarki także poza widokiem Raport */
var hiddenBefore=false;
window.addEventListener('beforeprint',function(){var v=$('view-report'); hiddenBefore=v.hidden; v.hidden=false; $('report').innerHTML=reportHTML();});
window.addEventListener('afterprint',function(){if(hiddenBefore) $('view-report').hidden=true;});

/* ---------- Start ---------- */
applyTheme();
try{if(navigator.storage&&navigator.storage.persist) navigator.storage.persist();}catch(e){}
renderMeds(); loadDayInputs(); tickTimer(true);
queueReminders();
})();
