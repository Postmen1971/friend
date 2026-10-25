(function(){
if(window.__tvv)return;window.__tvv=1;
var B=window.TVApp||{overlay:function(){},playAt:function(){},saveTile:function(){},ready:function(){}};
var KEY='__tvf:'+location.href.split('#')[0],ticks=0,io=null;
var on=false,manualOff=false,items=[],next=null,ov=null,cur=null,triedPlay=false;

var css=document.createElement('style');
css.textContent=
'html.__tvon,html.__tvon body{overflow:hidden!important;background:#0e0f13!important}'+
'html.__tvon body>*:not(#__tvov){display:none!important}'+
'#__tvov{position:fixed;left:0;top:0;right:0;bottom:0;z-index:2147483647;background:#0e0f13;color:#f2f3f5;font:16px system-ui,Roboto,sans-serif;overflow-y:auto;padding:14px 22px 40px;box-sizing:border-box;text-align:left}'+
'#__tvov *{box-sizing:border-box}'+
'#__tvov .h{display:flex;gap:10px;align-items:center;margin-bottom:12px}'+
'#__tvov .t{flex:1;font-size:18px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}'+
'#__tvov .b{background:#252833;border:3px solid transparent;border-radius:10px;padding:7px 12px;color:#f2f3f5;font-size:14px;cursor:pointer}'+
'#__tvov .g{display:grid;grid-template-columns:repeat(4,1fr);gap:12px}'+
'#__tvov .c{background:#1b1d24;border:3px solid transparent;border-radius:12px;overflow:hidden;cursor:pointer;transition:transform .1s}'+
'#__tvov .im{position:relative;width:100%;padding-top:56.25%;background:#252833 center/cover no-repeat}'+
'#__tvov .du{position:absolute;right:6px;bottom:6px;background:rgba(0,0,0,.75);color:#fff;font-size:12px;padding:2px 6px;border-radius:6px}'+
'#__tvov .n{padding:6px 8px;font-size:13px;line-height:1.3;height:3.2em;overflow:hidden}'+
'#__tvov .nx{display:flex;align-items:center;justify-content:center;font-size:18px;font-weight:600;min-height:110px}'+
'#__tvov .f{border-color:#ffffff!important;transform:scale(1.04)}';
(document.head||document.documentElement).appendChild(css);

function abs(u){try{return new URL(u,location.href).href}catch(e){return ''}}
function base(h){return (h||'').toLowerCase().split('.').slice(-2).join('.')}
function clean(s){return (s||'').replace(/\s+/g,' ').trim()}
function thumb(img){
  var lazy=img.getAttribute('data-src')||img.getAttribute('data-original')||img.getAttribute('data-thumb_url')||
           img.getAttribute('data-mediumthumb')||img.getAttribute('data-lazy')||img.getAttribute('data-lazy-src')||'';
  var c=img.currentSrc||img.src||'';
  if(c&&c.indexOf('data:')!==0)return c;
  return lazy?abs(lazy):c;
}

function collect(){
  var seen={},out=[],my=base(location.hostname),here=location.href.split('#')[0];
  var as=document.querySelectorAll('a[href]');
  for(var i=0;i<as.length;i++){
    var a=as[i];
    if(a.closest&&a.closest('#__tvov'))continue;
    var img=a.querySelector('img');if(!img)continue;
    var href=abs(a.getAttribute('href'));
    if(!href||/^javascript:/i.test(href))continue;
    var u;try{u=new URL(href)}catch(e){continue}
    if(base(u.hostname)!==my)continue;
    if(href.split('#')[0]===here)continue;
    if(u.pathname==='/'||u.pathname==='')continue;
    var w=parseInt(img.getAttribute('width'))||img.naturalWidth||0;
    if(w&&w<80)continue;
    var src=thumb(img);if(!src)continue;
    var ctx=clean((a.textContent||'')+' '+(a.parentElement?a.parentElement.textContent:'')).slice(0,400);
    var dm=ctx.match(/(?:^|\s)((?:\d{1,2}:)?\d{1,2}:\d{2})(?:\s|$)/);
    var title=clean(a.getAttribute('title')||img.getAttribute('alt')||a.textContent).replace(/(?:^|\s)(?:\d{1,2}:)?\d{1,2}:\d{2}(?=\s|$)/g,' ').trim();
    if(!title){var p=a.parentElement;title=p?clean(p.textContent).slice(0,90):''}
    if(title.length>90)title=title.slice(0,90)+'…';
    var k=href.split('#')[0];
    if(seen[k]){if(!seen[k].title&&title)seen[k].title=title;continue}
    seen[k]={href:k,img:src,title:title,dur:dm?dm[1]:''};
    out.push(seen[k]);
  }
  /* Wenn genug Einträge eine Laufzeit haben, sind das die Videos: nur diese zeigen */
  var vids=out.filter(function(x){return x.dur});
  if(vids.length>=8)return vids;
  return out;
}

function findNext(){
  var l=document.querySelector('link[rel="next"]');if(l&&l.href)return l.href;
  var a=document.querySelector('a[rel="next"]');if(a&&a.href)return a.href;
  var as=document.querySelectorAll('a[href]');
  for(var i=0;i<as.length;i++){
    var t=clean(as[i].textContent).toLowerCase(),c=(as[i].className||'')+'';
    if(/^(next|weiter|nächste( seite)?|next page|›|»|>)$/.test(t)||/(^|[\s_-])next([\s_-]|$)/i.test(c)){
      var h=abs(as[i].getAttribute('href'));if(h&&!/^javascript:/i.test(h))return h;
    }
  }
  return null;
}

/* Altersabfrage / großes Hinweisfenster der Seite sichtbar? Dann erst bestätigen lassen. */
function gateVisible(){
  var els=document.querySelectorAll('body *');
  var vw=innerWidth,vh=innerHeight;
  for(var i=0;i<els.length&&i<4000;i++){
    var e=els[i];if(e.id==='__tvov'||(e.closest&&e.closest('#__tvov')))continue;
    var cs=getComputedStyle(e);
    if(cs.position!=='fixed'||cs.display==='none'||cs.visibility==='hidden'||cs.opacity==='0')continue;
    var r=e.getBoundingClientRect();
    if(r.width<vw*0.3||r.height<vh*0.25)continue;
    var t=(e.textContent||'').slice(0,3000);
    if(/(\b18\b|18\+|age verification|i am 18|over 18|adult|altersverifi|über 18|volljährig|enter)/i.test(t))return true;
  }
  return false;
}

function videoTarget(){
  var vw=innerWidth,best=null;
  var vs=document.querySelectorAll('video');
  for(var i=0;i<vs.length;i++){var r=vs[i].getBoundingClientRect();if(r.width>vw*0.4&&r.height>120){best=vs[i];break}}
  if(!best){
    var ps=document.querySelectorAll('[id*="player" i],[class*="player" i]');
    for(var j=0;j<ps.length;j++){var q=ps[j].getBoundingClientRect();if(q.width>vw*0.4&&q.height>150&&q.top<innerHeight){best=ps[j];break}}
  }
  return best;
}

function el(tag,cls,txt){var e=document.createElement(tag);if(cls)e.className=cls;if(txt)e.textContent=txt;return e}

function render(){
  var keepHref=cur&&cur.getAttribute('data-h');
  if(!keepHref){try{keepHref=sessionStorage.getItem(KEY)}catch(e){}}
  if(!ov){ov=el('div');ov.id='__tvov';document.body.appendChild(ov)}
  ov.innerHTML='';
  var h=el('div','h');
  h.appendChild(el('div','t',clean(document.title)||location.hostname));
  var save=el('div','b','★ Als Kachel speichern');save.setAttribute('data-act','save');h.appendChild(save);
  var orig=el('div','b','Original-Seite');orig.setAttribute('data-act','orig');h.appendChild(orig);
  ov.appendChild(h);
  var g=el('div','g');
  items.forEach(function(it){
    var c=el('div','c');c.setAttribute('data-h',it.href);
    var im=el('div','im');im.setAttribute('data-img',it.img);
    if(it.dur)im.appendChild(el('span','du',it.dur));
    c.appendChild(im);c.appendChild(el('div','n',it.title||''));g.appendChild(c);
  });
  if(next){var nx=el('div','c nx','Nächste Seite ›');nx.setAttribute('data-h',next);g.appendChild(nx)}
  ov.appendChild(g);
  /* Vorschaubilder erst laden, wenn sie (fast) sichtbar sind */
  if(io)io.disconnect();
  var ims=ov.querySelectorAll('.im[data-img]');
  function load(im){var u=im.getAttribute('data-img');if(u){im.style.backgroundImage='url("'+u.replace(/"/g,'%22')+'")';im.removeAttribute('data-img')}}
  if(window.IntersectionObserver){
    io=new IntersectionObserver(function(es){es.forEach(function(x){if(x.isIntersecting){load(x.target);io.unobserve(x.target)}})},{root:ov,rootMargin:'400px 0px'});
    for(var i=0;i<ims.length;i++)io.observe(ims[i]);
  } else {for(var j=0;j<ims.length;j++)load(ims[j])}
  var f=keepHref&&ov.querySelector('[data-h="'+keepHref.replace(/"/g,'\\"')+'"]');
  focus(f||ov.querySelector('.c')||save);
}

function focus(e){if(cur)cur.classList.remove('f');cur=e;if(e){e.classList.add('f');
  var r=e.getBoundingClientRect();if(r.top<60||r.bottom>innerHeight-20)ov.scrollTop+=r.top-innerHeight/2+r.height/2;}}

function show(){
  if(on)return;on=true;
  document.documentElement.classList.add('__tvon');
  render();B.overlay(true);
}
function hide(){
  if(!on)return;on=false;
  document.documentElement.classList.remove('__tvon');
  if(ov){ov.remove();ov=null;cur=null}
  B.overlay(false);
}

function activate(e){
  if(!e)return;
  var act=e.getAttribute('data-act');
  if(act==='save'){B.saveTile();return}
  if(act==='orig'){manualOff=true;hide();return}
  var h=e.getAttribute('data-h');
  if(h){try{sessionStorage.setItem(KEY,h)}catch(x){}
    var n=e.querySelector('.n');if(n)n.textContent='Lädt …';location.href=h}
}

window.addEventListener('keydown',function(ev){
  if(!on)return;
  var k=ev.key,d=null;
  if(k==='ArrowUp')d=[0,-1];else if(k==='ArrowDown')d=[0,1];else if(k==='ArrowLeft')d=[-1,0];else if(k==='ArrowRight')d=[1,0];
  if(k==='Enter'||k===' '){ev.preventDefault();ev.stopImmediatePropagation();activate(cur);return}
  if(!d)return;
  ev.preventDefault();ev.stopImmediatePropagation();
  var list=ov.querySelectorAll('.c,.b');if(!cur){focus(list[0]);return}
  var r=cur.getBoundingClientRect(),cx=r.left+r.width/2,cy=r.top+r.height/2,best=null,bd=1e9;
  for(var i=0;i<list.length;i++){var e=list[i];if(e===cur)continue;
    var q=e.getBoundingClientRect(),dx=q.left+q.width/2-cx,dy=q.top+q.height/2-cy;
    if(d[0]&&(dx*d[0]<=5))continue;if(d[1]&&(dy*d[1]<=5))continue;
    var s=(d[0]?Math.abs(dx):Math.abs(dy))+(d[0]?Math.abs(dy):Math.abs(dx))*3;
    if(s<bd){bd=s;best=e}}
  if(best)focus(best);
},true);

setInterval(function(){
  if(on){var vs=document.querySelectorAll('video');for(var i=0;i<vs.length;i++){try{if(!vs[i].paused)vs[i].pause()}catch(e){}}}
},1000);

window.__tvv_show=function(){manualOff=false;check(true)};
window.addEventListener('pageshow',function(){if(on)B.overlay(true)});

function check(force){
  ticks++;
  if(manualOff&&!force)return;
  if(on)return;
  if(ticks>=5)B.ready();
  var v=videoTarget();
  if(v){B.ready();
    if(!triedPlay){triedPlay=true;
      setTimeout(function(){var r=v.getBoundingClientRect();B.playAt(r.left+r.width/2,r.top+r.height/2)},900);}
    return;
  }
  if(gateVisible()){B.ready();return}
  var it=collect();
  if(it.length>=8){items=it;next=findNext();show()}
}

/* Erst kurz warten, damit Player-Bereiche von Videoseiten schon da sind */
var n=0,t;
setTimeout(function(){check(false);t=setInterval(function(){n++;check(false);if(n>20)clearInterval(t)},1000)},1500);
})();
