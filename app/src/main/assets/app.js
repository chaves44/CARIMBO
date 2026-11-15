'use strict';
/* Carimbo de Fotos · A R Chaves Engenharia */

const A = window.Android || null;
const $ = id => document.getElementById(id);
const MENOS = '−';

/* ================= dados guardados ================= */
const CFG_PADRAO = {auto:true, bairro:true, uf:true, alt:true, prec:false, pos:'br', tam:'1', lado:'4000', os:'', obs:''};
let cfg = Object.assign({}, CFG_PADRAO, ler('carimbo.cfg') || {});
let fotos = ler('carimbo.fotos') || [];
const minis = {};            // id → dataURL da miniatura
const exifCache = {};        // id → objeto EXIF original

function ler(k){ try{ const v = localStorage.getItem(k); return v ? JSON.parse(v) : null }catch(e){ return null } }
function gravar(k,v){ try{ localStorage.setItem(k, JSON.stringify(v)) }catch(e){} }
function guardarFotos(){ gravar('carimbo.fotos', fotos) }
function guardarCfg(){ gravar('carimbo.cfg', cfg) }
function guardarMini(id,url){ minis[id]=url; try{ localStorage.setItem('carimbo.mini.'+id, url) }catch(e){} }
function lerMini(id){ if(minis[id]) return minis[id]; try{ const v=localStorage.getItem('carimbo.mini.'+id); if(v) minis[id]=v; return v }catch(e){ return null } }
function apagarMini(id){ delete minis[id]; try{ localStorage.removeItem('carimbo.mini.'+id) }catch(e){} }

/* ================= utilidades ================= */
const pad = n => String(n).padStart(2,'0');
function num(v,d){ const s=Math.abs(v).toLocaleString('pt-BR',{minimumFractionDigits:d,maximumFractionDigits:d,useGrouping:false}); return (v<0?MENOS:'')+s }
function fData(d){ return pad(d.getDate())+'/'+pad(d.getMonth()+1)+'/'+d.getFullYear() }
function fHora(d){ return pad(d.getHours())+':'+pad(d.getMinutes())+':'+pad(d.getSeconds()) }
function fArq(d){ return d.getFullYear()+pad(d.getMonth()+1)+pad(d.getDate())+'_'+pad(d.getHours())+pad(d.getMinutes())+pad(d.getSeconds()) }
function rat(r){ return Array.isArray(r) ? r[0]/(r[1]||1) : +r }
function dms(a){ return rat(a[0])+rat(a[1])/60+rat(a[2])/3600 }
function novoId(){ return Date.now().toString(36)+Math.random().toString(36).slice(2,6) }
function esc(s){ return String(s==null?'':s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c])) }
function dorme(ms){ return new Promise(r=>setTimeout(r,ms)) }
function aviso(t){ const e=$('toast'); e.textContent=t; e.classList.add('on'); clearTimeout(aviso.t); aviso.t=setTimeout(()=>e.classList.remove('on'),2600) }
function espera(t){ if(t){ $('esperaTxt').textContent=t; $('espera').hidden=false } else $('espera').hidden=true }
function perguntar(txt, sim){ return new Promise(res=>{ $('dlgTxt').innerHTML=txt; $('dlgSim').textContent=sim||'Confirmar'; $('dialogo').hidden=false;
  const fim=v=>{ $('dialogo').hidden=true; $('dlgSim').onclick=$('dlgNao').onclick=null; res(v) };
  $('dlgSim').onclick=()=>fim(true); $('dlgNao').onclick=()=>fim(false); }) }
function haversine(a,b,c,d){ const r=Math.PI/180; const x=Math.sin((c-a)*r/2)**2+Math.cos(a*r)*Math.cos(c*r)*Math.sin((d-b)*r/2)**2; return 12742*Math.asin(Math.sqrt(x)) }
function lerCoord(s){
  if(!s) return null;
  s=s.replace(/[−–—]/g,'-').replace(/°/g,' ').trim();
  const m=s.match(/(-?\d+(?:[.,]\d+)?)\s*([NSns])?[\s,;]+(-?\d+(?:[.,]\d+)?)\s*([EWOLewol])?/);
  if(!m) return null;
  let la=parseFloat(m[1].replace(',','.')), lo=parseFloat(m[3].replace(',','.'));
  if(m[2]&&/s/i.test(m[2])) la=-Math.abs(la);
  if(m[4]&&/[wo]/i.test(m[4])) lo=-Math.abs(lo);
  if(isNaN(la)||isNaN(lo)||Math.abs(la)>90||Math.abs(lo)>180) return null;
  return {lat:la, lon:lo};
}

/* ================= municípios (sem internet) ================= */
const MUN = (window.MUN_RAW||'').split(';').map(s=>{const p=s.split('|');return {n:p[0],uf:p[1],la:+p[2],lo:+p[3]}});
function cidadeProxima(lat,lon){ let b=null,bd=1e9; for(const m of MUN){ const d=haversine(lat,lon,m.la,m.lo); if(d<bd){bd=d;b=m} } return b?{cidade:b.n,uf:b.uf}:null }

/* ================= endereço pela internet (OpenStreetMap) ================= */
const geoCache = ler('carimbo.geo') || {};
let geoFila = Promise.resolve(), geoUltimo = 0;
function endereco(lat,lon){
  const k=lat.toFixed(4)+','+lon.toFixed(4);
  if(geoCache[k]) return Promise.resolve(geoCache[k]);
  const p = geoFila.then(async()=>{
    if(geoCache[k]) return geoCache[k];
    const falta = 1100-(Date.now()-geoUltimo); if(falta>0) await dorme(falta);
    geoUltimo = Date.now();
    const ctl = new AbortController(); const t=setTimeout(()=>ctl.abort(),8000);
    try{
      const r = await fetch('https://nominatim.openstreetmap.org/reverse?format=jsonv2&addressdetails=1&zoom=18&accept-language=pt-BR&lat='+lat+'&lon='+lon,{signal:ctl.signal});
      const j = await r.json(); const a=j.address||{};
      const iso = a['ISO3166-2-lvl4']||''; const uf = iso.split('-')[1]||'';
      const res = {
        bairro: a.suburb||a.neighbourhood||a.quarter||a.city_district||a.residential||a.hamlet||'',
        cidade: a.city||a.town||a.municipality||a.village||'',
        uf
      };
      if(!res.cidade){ const c=cidadeProxima(lat,lon); if(c){res.cidade=c.cidade; res.uf=res.uf||c.uf} }
      geoCache[k]=res; const ks=Object.keys(geoCache); if(ks.length>400) delete geoCache[ks[0]];
      gravar('carimbo.geo',geoCache);
      return res;
    }catch(e){ return null }finally{ clearTimeout(t) }
  });
  geoFila = p.catch(()=>{});
  return p;
}
async function preencherLocal(f){
  if(f.lat==null) return;
  const off = cidadeProxima(f.lat,f.lon);
  if(off){ if(!f.cidade) f.cidade=off.cidade; if(!f.uf) f.uf=off.uf }
  const on = await endereco(f.lat,f.lon);
  if(on){ if(!f.cidadeManual){ f.cidade=on.cidade||f.cidade; f.uf=on.uf||f.uf } if(!f.bairroManual) f.bairro=on.bairro||f.bairro||'' }
}

/* ================= leitura das fotos ================= */
async function binarioDe(url){ const b=await (await fetch(url)).blob(); return await blobBin(b) }
function blobBin(b){ return new Promise((res,rej)=>{ const r=new FileReader(); r.onload=()=>res(r.result); r.onerror=rej; r.readAsBinaryString(b) }) }
function urlFoto(f){ return '/fotos/'+encodeURIComponent(f.arquivo) }

async function lerExif(f){
  if(exifCache[f.id]!==undefined) return exifCache[f.id];
  let ex=null;
  try{ const bin=await binarioDe(urlFoto(f)); ex=piexif.load(bin) }catch(e){ ex=null }
  exifCache[f.id]=ex; return ex;
}

async function novasFotos(lista){
  if(!lista||!lista.length){ espera(); return }
  espera('Preparando '+lista.length+(lista.length>1?' fotos…':' foto…'));
  const novas=[];
  for(const it of lista){
    const f = {id:novoId(), arquivo:it.arquivo, nomeOrig:it.nome||it.arquivo, origem:it.origem,
      data:null, dataFonte:'', lat:null, lon:null, alt:null, prec:null, gpsFonte:'',
      cidade:'', uf:'', bairro:'', os:cfg.os, obs:cfg.obs, pos:cfg.pos, tam:cfg.tam,
      rot:0, corte:null, bri:0, con:0, uri:'', salva:false};
    const ex = await lerExif(f);
    if(ex){
      const dt=(ex.Exif&&(ex.Exif[piexif.ExifIFD.DateTimeOriginal]||ex.Exif[piexif.ExifIFD.DateTimeDigitized]))||(ex['0th']&&ex['0th'][piexif.ImageIFD.DateTime]);
      const m=dt&&String(dt).match(/(\d{4}):(\d\d):(\d\d)[ T](\d\d):(\d\d):(\d\d)/);
      if(m){ f.data=new Date(+m[1],m[2]-1,+m[3],+m[4],+m[5],+m[6]).getTime(); f.dataFonte='camera' }
      const g=ex.GPS||{}; const la=g[piexif.GPSIFD.GPSLatitude], lo=g[piexif.GPSIFD.GPSLongitude];
      if(la&&lo){
        let a=dms(la), b=dms(lo);
        if(String(g[piexif.GPSIFD.GPSLatitudeRef]).toUpperCase()==='S') a=-a;
        if(String(g[piexif.GPSIFD.GPSLongitudeRef]).toUpperCase()==='W') b=-b;
        if(isFinite(a)&&isFinite(b)&&!(a===0&&b===0)){ f.lat=a; f.lon=b; f.gpsFonte='foto' }
      }
      const al=g[piexif.GPSIFD.GPSAltitude];
      if(al&&f.lat!=null){ let v=rat(al); if(g[piexif.GPSIFD.GPSAltitudeRef]===1) v=-v; if(isFinite(v)) f.alt=v }
    }
    // GPS do celular no momento da foto (quando a câmera não gravou)
    if(f.lat==null && it.local && it.local.idade < 180000){
      f.lat=it.local.lat; f.lon=it.local.lon; f.alt=it.local.alt!=null?it.local.alt:null; f.prec=it.local.prec; f.gpsFonte='celular';
    } else if(it.local && f.gpsFonte==='foto' && it.local.prec!=null && haversine(f.lat,f.lon,it.local.lat,it.local.lon)*1000 < 100){
      f.prec=it.local.prec;
    }
    if(!f.data){ f.data=it.quando||Date.now(); f.dataFonte = it.origem==='camera' ? 'camera' : 'arquivo' }
    novas.push(f);
  }
  // preenche cidade e bairro
  espera('Buscando cidade e bairro…');
  await Promise.race([Promise.all(novas.map(preencherLocal)), dorme(9000)]);
  for(const f of novas){ if(f.lat!=null && !f.cidade){ const c=cidadeProxima(f.lat,f.lon); if(c){f.cidade=c.cidade; f.uf=c.uf} } }
  fotos.push(...novas); fotos.sort((a,b)=>a.data-b.data); guardarFotos();
  for(const f of novas){ await gerarMini(f) }
  desenharLista();
  if(cfg.auto){
    for(let k=0;k<novas.length;k++){ espera('Salvando na galeria '+(k+1)+' de '+novas.length+'…'); await salvarFoto(novas[k]) }
    desenharLista();
  }
  espera();
  const semGps = novas.filter(f=>f.lat==null).length;
  if(semGps) aviso(semGps+(semGps>1?' fotos vieram':' foto veio')+' sem GPS. Toque nela para colocar as coordenadas.');
  else aviso(novas.length>1? novas.length+' fotos carimbadas' : 'Foto carimbada');
}

/* ================= desenho ================= */
let bmpCache = {id:null, bmp:null};
async function bitmapDe(f){
  if(bmpCache.id===f.id && bmpCache.bmp) return bmpCache.bmp;
  if(bmpCache.bmp && bmpCache.bmp.close) bmpCache.bmp.close();
  bmpCache={id:null,bmp:null};
  const blob=await (await fetch(urlFoto(f))).blob();
  let bmp;
  try{ bmp=await createImageBitmap(blob,{imageOrientation:'from-image'}) }
  catch(e){ bmp=await new Promise((res,rej)=>{ const i=new Image(); i.onload=()=>res(i); i.onerror=rej; i.src=URL.createObjectURL(blob) }) }
  bmpCache={id:f.id,bmp}; return bmp;
}

function linhasCarimbo(f){
  const d=new Date(f.data), L=[];
  L.push(fData(d)+'   '+fHora(d));
  if(f.lat!=null){
    L.push('Lat '+num(f.lat,6)+'°   Long '+num(f.lon,6)+'°');
    const ex=[];
    if(cfg.alt && f.alt!=null) ex.push('Altitude '+num(f.alt,0)+' m');
    if(cfg.prec && f.prec!=null) ex.push('Precisão '+num(f.prec,0)+' m');
    if(ex.length) L.push(ex.join('   '));
  }
  const local=[]; if(cfg.bairro && f.bairro) local.push(f.bairro);
  if(f.cidade) local.push(f.cidade + (cfg.uf && f.uf ? ', '+f.uf : ''));
  if(local.length) L.push(local.join(', '));
  if(f.os && f.os.trim()) L.push('OS '+f.os.trim());
  if(f.obs && f.obs.trim()) L.push(f.obs.trim());
  return L;
}

function carimbar(ctx,W,H,f){
  const L=linhasCarimbo(f), k=parseFloat(f.tam||'1');
  const fs=Math.max(10,Math.round(Math.min(W,H)*0.030*k)), f1=Math.round(fs*1.12);
  const p=Math.round(fs*0.6), gap=Math.round(fs*0.28), mg=Math.round(Math.min(W,H)*0.02), barra=Math.round(fs*0.35);
  const fonte=(peso,t)=>peso+' '+t+'px system-ui,Roboto,"Segoe UI",Arial,sans-serif';
  let bw=0; L.forEach((t,i)=>{ ctx.font=fonte(i?600:700,i?fs:f1); bw=Math.max(bw,ctx.measureText(t).width) });
  bw=Math.min(bw, W-2*mg-2*p-barra);
  const bh=p*2+f1+(L.length-1)*(fs+gap), cw=bw+p*2+barra;
  const pos=f.pos||'br', dir=pos.includes('r');
  const x=dir?W-mg-cw:mg, y=pos.includes('b')?H-mg-bh:mg, r=Math.round(fs*0.4);
  ctx.save();
  ctx.fillStyle='rgba(0,0,0,0.55)';
  ctx.beginPath(); ctx.moveTo(x+r,y); ctx.arcTo(x+cw,y,x+cw,y+bh,r); ctx.arcTo(x+cw,y+bh,x,y+bh,r); ctx.arcTo(x,y+bh,x,y,r); ctx.arcTo(x,y,x+cw,y,r); ctx.closePath(); ctx.fill();
  ctx.fillStyle='#f39200'; const bl=Math.round(fs*0.18);
  ctx.fillRect(dir? x+cw-bl-Math.round(fs*0.12) : x+Math.round(fs*0.12), y+p, bl, bh-p*2);
  ctx.fillStyle='#fff'; ctx.textBaseline='top'; ctx.shadowColor='rgba(0,0,0,.6)'; ctx.shadowBlur=Math.round(fs*0.15);
  let ty=y+p;
  L.forEach((t,i)=>{ const s=i?fs:f1; ctx.font=fonte(i?600:700,s);
    if(dir){ ctx.textAlign='right'; ctx.fillText(t, x+cw-p-barra, ty, bw) } else { ctx.textAlign='left'; ctx.fillText(t, x+p+barra, ty, bw) }
    ty+=s+gap });
  ctx.restore();
}

/** Desenha a foto com giro, corte, ajustes e carimbo. */
async function desenhar(f, ladoMax, op){
  op=op||{};
  const bmp=await bitmapDe(f);
  const W0=bmp.width, H0=bmp.height, rot=((f.rot||0)%360+360)%360;
  const RW= rot%180 ? H0 : W0, RH= rot%180 ? W0 : H0;
  const c = (!op.semCorte && f.corte) ? f.corte : {x:0,y:0,w:1,h:1};
  const cx=c.x*RW, cy=c.y*RH, cw=c.w*RW, ch=c.h*RH;
  const s = ladoMax ? Math.min(1, ladoMax/Math.max(cw,ch)) : 1;
  const cv=document.createElement('canvas'); cv.width=Math.max(1,Math.round(cw*s)); cv.height=Math.max(1,Math.round(ch*s));
  const ctx=cv.getContext('2d');
  ctx.fillStyle='#000'; ctx.fillRect(0,0,cv.width,cv.height);
  const filtros=[]; if(f.bri) filtros.push('brightness('+(1+f.bri/100)+')'); if(f.con) filtros.push('contrast('+(1+f.con/100)+')');
  ctx.filter = filtros.length ? filtros.join(' ') : 'none';
  ctx.save(); ctx.scale(s,s); ctx.translate(-cx,-cy); ctx.translate(RW/2,RH/2); ctx.rotate(rot*Math.PI/180);
  ctx.imageSmoothingQuality='high';
  ctx.drawImage(bmp,-W0/2,-H0/2); ctx.restore();
  ctx.filter='none';
  if(!op.semCarimbo) carimbar(ctx,cv.width,cv.height,f);
  return cv;
}

async function gerarMini(f){
  try{ const cv=await desenhar(f,520); guardarMini(f.id, cv.toDataURL('image/jpeg',0.75)); cv.width=cv.height=1 }catch(e){}
}

/* ================= salvar na galeria ================= */
function nomeArquivo(f){
  const os=(f.os||'').trim().replace(/[^0-9A-Za-z]+/g,'.').replace(/^\.|\.$/g,'');
  return (os?'OS_'+os:'FOTO')+'_'+fArq(new Date(f.data))+'.jpg';
}
function paraDMS(v){ v=Math.abs(v); const d=Math.floor(v), mf=(v-d)*60, m=Math.floor(mf), s=Math.round((mf-m)*60*10000); return [[d,1],[m,1],[s,10000]] }

async function jpegFinal(f){
  const lado=parseInt(cfg.lado||'0',10)||0;
  const cv=await desenhar(f,lado);
  let blob=await new Promise(r=>cv.toBlob(r,'image/jpeg',0.92));
  const W=cv.width,H=cv.height; cv.width=cv.height=1;
  let bin=await blobBin(blob);
  try{
    const orig=await lerExif(f);
    const ex = orig ? JSON.parse(JSON.stringify(orig)) : {};
    ex['0th']=ex['0th']||{}; ex.Exif=ex.Exif||{}; ex.GPS=ex.GPS||{}; ex.Interop=ex.Interop||{};
    ex['0th'][piexif.ImageIFD.Orientation]=1;
    ex['0th'][piexif.ImageIFD.Software]='Carimbo de Fotos';
    delete ex['0th'][piexif.ImageIFD.ImageWidth]; delete ex['0th'][piexif.ImageIFD.ImageLength];
    ex.Exif[piexif.ExifIFD.PixelXDimension]=W; ex.Exif[piexif.ExifIFD.PixelYDimension]=H;
    delete ex.Exif[piexif.ExifIFD.MakerNote];
    ex['1st']={}; ex.thumbnail=null;
    const ds=(d=>d.getFullYear()+':'+pad(d.getMonth()+1)+':'+pad(d.getDate())+' '+fHora(d))(new Date(f.data));
    if(!ex.Exif[piexif.ExifIFD.DateTimeOriginal]) ex.Exif[piexif.ExifIFD.DateTimeOriginal]=ds;
    if(f.lat!=null && !ex.GPS[piexif.GPSIFD.GPSLatitude]){
      ex.GPS[piexif.GPSIFD.GPSLatitudeRef]=f.lat<0?'S':'N'; ex.GPS[piexif.GPSIFD.GPSLatitude]=paraDMS(f.lat);
      ex.GPS[piexif.GPSIFD.GPSLongitudeRef]=f.lon<0?'W':'E'; ex.GPS[piexif.GPSIFD.GPSLongitude]=paraDMS(f.lon);
      if(f.alt!=null){ ex.GPS[piexif.GPSIFD.GPSAltitudeRef]=f.alt<0?1:0; ex.GPS[piexif.GPSIFD.GPSAltitude]=[Math.round(Math.abs(f.alt)*100),100] }
    }
    const desc=linhasCarimbo(f).join(' | ');
    ex['0th'][piexif.ImageIFD.ImageDescription]=desc.normalize('NFD').replace(/[̀-ͯ−]/g,c=>c==='−'?'-':'');
    bin=piexif.insert(piexif.dump(ex), bin);
  }catch(e){ console.log('exif', e) }
  return bin;
}

async function salvarFoto(f){
  try{
    const bin=await jpegFinal(f);
    if(A){
      const uri=A.salvarImagem(btoa(bin), nomeArquivo(f), f.uri||'');
      if(uri){ f.uri=uri; f.salva=true } else { aviso('Não consegui salvar na galeria.'); return false }
    } else {
      // fora do celular (teste): guarda para conferência
      window.__ultimaSalva = bin; f.salva=true;
    }
    guardarFotos(); return true;
  }catch(e){ aviso('Erro ao salvar: '+(e&&e.message||e)); return false }
}

/* ================= tela inicial ================= */
function desenharLista(){
  const g=$('grade'); g.innerHTML='';
  $('contagem').textContent = fotos.length ? fotos.length+(fotos.length>1?' fotos nesta vistoria':' foto nesta vistoria') : 'Nenhuma foto';
  $('bLimpar').style.display = fotos.length?'':'none';
  $('rodape').style.display = fotos.length?'flex':'none';
  $('bAplicarTodas').style.display = fotos.length?'':'none';
  const pend=fotos.filter(f=>!f.salva).length;
  $('bSalvarTodas').textContent = pend ? 'Salvar '+pend+(pend>1?' fotos':' foto') : 'Tudo salvo ✓';
  $('bSalvarTodas').disabled = !pend;
  if(!fotos.length){ g.innerHTML='<div class="vazio">Tire uma foto ou escolha da galeria.<br>Ela sai com data, hora, coordenadas, cidade e bairro.</div>'; return }
  [...fotos].reverse().forEach(f=>{
    const d=document.createElement('div'); d.className='mini-foto';
    const m=lerMini(f.id);
    d.innerHTML=(m?'<img src="'+m+'" alt="">':'<div class="carregando">…</div>')+
      '<div class="selos">'+(f.salva?'<span class="selo ok">salva</span>':'<span class="selo pend">não salva</span>')+
      (f.lat==null?'<span class="selo warn">sem GPS</span>':'')+'</div>';
    d.onclick=()=>abrirEditor(f.id);
    g.appendChild(d);
    if(!m) gerarMini(f).then(()=>{ const u=lerMini(f.id); if(u) d.querySelector('.carregando').outerHTML='<img src="'+u+'" alt="">' });
  });
}

$('os').value=cfg.os; $('obs').value=cfg.obs;
$('os').oninput=()=>{ cfg.os=$('os').value; guardarCfg() };
$('obs').oninput=()=>{ cfg.obs=$('obs').value; guardarCfg() };
$('bCamera').onclick=()=>{ if(A) A.tirarFoto(); else aviso('A câmera só funciona no celular') };
$('bGaleria').onclick=()=>{ if(A) A.escolherFotos(); else aviso('A galeria só funciona no celular') };
$('bAplicarTodas').onclick=async()=>{
  if(!await perguntar('Colocar a OS <b>'+esc(cfg.os||'(vazia)')+'</b> e a linha extra em todas as '+fotos.length+' fotos da lista?','Aplicar')) return;
  espera('Atualizando…');
  for(const f of fotos){ f.os=cfg.os; f.obs=cfg.obs; f.salva=false; await gerarMini(f) }
  guardarFotos(); desenharLista(); espera(); aviso('Pronto. Toque em Salvar para gravar na galeria.');
};
$('bSalvarTodas').onclick=async()=>{
  const pend=fotos.filter(f=>!f.salva);
  for(let k=0;k<pend.length;k++){ espera('Salvando '+(k+1)+' de '+pend.length+'…'); await salvarFoto(pend[k]) }
  espera(); desenharLista(); aviso('Fotos salvas em Imagens › Carimbo');
};
$('bCompartilhar').onclick=async()=>{
  const pend=fotos.filter(f=>!f.salva);
  for(let k=0;k<pend.length;k++){ espera('Salvando '+(k+1)+' de '+pend.length+'…'); await salvarFoto(pend[k]) }
  espera(); desenharLista();
  const uris=fotos.filter(f=>f.uri).map(f=>f.uri);
  if(A&&uris.length) A.compartilhar(JSON.stringify(uris));
};
$('bLimpar').onclick=async()=>{
  const pend=fotos.filter(f=>!f.salva).length;
  if(!await perguntar('Começar uma nova vistoria? A lista vai ser limpa.'+(pend?'<br><br><b>'+pend+' foto(s) ainda não foram salvas</b> e vão ser perdidas.':'<br><br>As fotos salvas continuam na galeria.'),'Limpar lista')) return;
  fotos.forEach(f=>{ if(A) A.apagarOriginal(f.arquivo); apagarMini(f.id) });
  fotos=[]; guardarFotos(); cfg.os=''; cfg.obs=''; guardarCfg(); $('os').value=''; $('obs').value='';
  desenharLista();
};

/* ================= GPS ao vivo ================= */
let gpsUlt=null, gpsGeoPos=null, gpsGeo=null;
async function atualizarGps(){
  if(!A){ $('gpsTxt').textContent='GPS só funciona no celular'; return }
  if(!A.gpsPermitido()){ $('gpsPonto').className='ponto ruim'; $('gpsTxt').textContent='Localização não permitida'; $('bPermitir').style.display=''; return }
  $('bPermitir').style.display='none';
  const s=A.localizacao(); if(!s){ $('gpsPonto').className='ponto'; $('gpsTxt').textContent='Procurando GPS… (ligue a localização do celular)'; return }
  const l=JSON.parse(s); gpsUlt=l;
  const velho=l.idade>120000;
  $('gpsPonto').className='ponto '+(velho?'ruim':l.prec<=15?'ok':'meh');
  let txt='GPS ±'+num(l.prec,0)+' m'+(velho?' (antigo)':'');
  if(!gpsGeoPos || haversine(gpsGeoPos.lat,gpsGeoPos.lon,l.lat,l.lon)>0.08){
    gpsGeoPos={lat:l.lat,lon:l.lon};
    const off=cidadeProxima(l.lat,l.lon); gpsGeo={cidade:off?off.cidade:'',bairro:''};
    endereco(l.lat,l.lon).then(r=>{ if(r) gpsGeo=r; mostrarGps() });
  }
  mostrarGps(txt);
}
function mostrarGps(txt){
  if(txt) mostrarGps.t=txt;
  const g=gpsGeo; const lugar=g?[g.bairro,g.cidade].filter(Boolean).join(', '):'';
  $('gpsTxt').textContent=(mostrarGps.t||'')+(lugar?' · '+lugar:'');
}
$('bPermitir').onclick=()=>A&&A.pedirPermissoes();
setInterval(atualizarGps,3000); setTimeout(atualizarGps,600);

/* ================= mensagens do Android ================= */
window.doAndroid = function(txt){
  let m; try{ m=JSON.parse(txt) }catch(e){ return }
  if(m.tipo==='novas') novasFotos(m.arquivos);
  else if(m.tipo==='lendo') espera('Copiando '+m.n+(m.n>1?' fotos…':' foto…'));
  else if(m.tipo==='erro') aviso(m.msg);
  else if(m.tipo==='permissoes') atualizarGps();
};

/* ================= editor ================= */
let ed=null;          // cópia em edição
let edOrig=null;      // foto original da lista
let desenhando=false, redesenhar=false, corteAtivo=false, corteTmp=null;

function abrirEditor(id){
  edOrig=fotos.find(f=>f.id===id); if(!edOrig) return;
  ed=JSON.parse(JSON.stringify(edOrig));
  $('editor').hidden=false; history.pushState({ed:1},'');
  $('eTitulo').textContent='Foto '+(fotos.indexOf(edOrig)+1)+' de '+fotos.length;
  $('eOs').value=ed.os||''; $('eObs').value=ed.obs||''; $('eBairro').value=ed.bairro||''; $('eCidade').value=ed.cidade||'';
  $('ePos').value=ed.pos||'br'; $('eTam').value=ed.tam||'1';
  $('eBri').value=ed.bri||0; $('eCon').value=ed.con||0; mostrarAjustes();
  trocarAba('carimbo'); sairCorte();
  preencherDados();
  previa();
}
function fecharEditor(){ $('editor').hidden=true; ed=edOrig=null; sairCorte() }
window.voltar=function(){
  if(!$('dialogo').hidden){ $('dlgNao').click(); return true }
  if(!$('espera').hidden) return true;
  if(!$('ajustes').hidden){ history.back(); return true }
  if(!$('editor').hidden){ if(corteAtivo){ sairCorte(); previa(); return true } history.back(); return true }
  return false;
};
window.addEventListener('popstate',()=>{ if(!$('editor').hidden) fecharEditor(); if(!$('ajustes').hidden) $('ajustes').hidden=true });

function preencherDados(){
  const d=new Date(ed.data);
  const fonteData={camera:'gravada pela câmera',arquivo:'data do arquivo',manual:'digitada'}[ed.dataFonte]||'';
  const fonteGps={foto:'gravado na foto',celular:'GPS do celular na hora da foto',manual:'digitado'}[ed.gpsFonte]||'';
  $('eInfo').innerHTML=
    '<div>📅 <b>'+fData(d)+' '+fHora(d)+'</b> <span class="cad">'+fonteData+'</span></div>'+
    (ed.lat!=null ? '<div>📍 <b>'+num(ed.lat,6)+'°  '+num(ed.lon,6)+'°</b> <span class="cad">'+fonteGps+'</span></div>'+
      (ed.prec!=null?'<div>🎯 Precisão '+num(ed.prec,0)+' m</div>':'')+(ed.alt!=null?'<div>⛰ Altitude '+num(ed.alt,0)+' m</div>':'')
      : '<div>📍 <b>Sem coordenadas</b></div>')+
    '<div>🏙 '+esc([ed.bairro,ed.cidade,ed.uf].filter(Boolean).join(', ')||'Cidade não encontrada')+'</div>'+
    '<div class="cad">Arquivo original: '+esc(ed.nomeOrig)+'</div>'+
    (ed.dataFonte==='camera'&&ed.gpsFonte!=='manual'&&ed.lat!=null?'<div class="dica">🔒 Data, hora e coordenadas vêm da captura e não podem ser alteradas.</div>':'');
  $('eSemGps').hidden = !(ed.lat==null || ed.gpsFonte==='manual');
  $('eCoord').value = ed.gpsFonte==='manual' ? num(ed.lat,6)+' '+num(ed.lon,6) : '';
  $('eSemData').hidden = !(ed.dataFonte==='arquivo' || ed.dataFonte==='manual');
  $('eData').value = (x=>x.getFullYear()+'-'+pad(x.getMonth()+1)+'-'+pad(x.getDate())+'T'+fHora(x))(d);
}

async function previa(){
  if(!ed) return;
  if(desenhando){ redesenhar=true; return }
  desenhando=true;
  try{
    const alvo = Math.min(1600, Math.round(Math.max(window.innerWidth, window.innerHeight*0.52) * (window.devicePixelRatio||1)));
    const cv=await desenhar(ed, alvo, corteAtivo?{semCorte:true,semCarimbo:true}:{});
    const c=$('eCanvas'); c.width=cv.width; c.height=cv.height; c.getContext('2d').drawImage(cv,0,0); cv.width=cv.height=1;
    if(corteAtivo) posicionarCorte();
  }catch(e){ aviso('Não consegui abrir a foto') }
  desenhando=false;
  if(redesenhar){ redesenhar=false; previa() }
}

function trocarAba(a){
  document.querySelectorAll('.aba').forEach(b=>b.classList.toggle('ativa',b.dataset.aba===a));
  $('pCarimbo').hidden=a!=='carimbo'; $('pFoto').hidden=a!=='foto'; $('pDados').hidden=a!=='dados';
}
document.querySelectorAll('.aba').forEach(b=>b.onclick=()=>trocarAba(b.dataset.aba));

$('eOs').oninput=()=>{ ed.os=$('eOs').value; previa() };
$('eObs').oninput=()=>{ ed.obs=$('eObs').value; previa() };
$('eBairro').oninput=()=>{ ed.bairro=$('eBairro').value; ed.bairroManual=true; previa() };
$('eCidade').oninput=()=>{ ed.cidade=$('eCidade').value; ed.cidadeManual=true; previa() };
$('ePos').onchange=()=>{ ed.pos=$('ePos').value; previa() };
$('eTam').onchange=()=>{ ed.tam=$('eTam').value; previa() };
function mostrarAjustes(){ $('eBriV').textContent=num(+$('eBri').value,0)+' %'; $('eConV').textContent=num(+$('eCon').value,0)+' %' }
$('eBri').oninput=()=>{ ed.bri=+$('eBri').value; mostrarAjustes(); previa() };
$('eCon').oninput=()=>{ ed.con=+$('eCon').value; mostrarAjustes(); previa() };
$('eGirarE').onclick=()=>{ ed.rot=((ed.rot||0)+270)%360; ed.corte=girarCorte(ed.corte,-90); previa() };
$('eGirarD').onclick=()=>{ ed.rot=((ed.rot||0)+90)%360; ed.corte=girarCorte(ed.corte,90); previa() };
function girarCorte(c,ang){ if(!c) return null; return ang>0 ? {x:1-c.y-c.h, y:c.x, w:c.h, h:c.w} : {x:c.y, y:1-c.x-c.w, w:c.h, h:c.w} }
$('eRestaurar').onclick=()=>{ ed.rot=0; ed.corte=null; ed.bri=0; ed.con=0; $('eBri').value=0; $('eCon').value=0; mostrarAjustes(); sairCorte(); previa() };

$('eCoord').onchange=async()=>{
  const p=lerCoord($('eCoord').value);
  if(!p){ aviso('Não entendi. Use algo como −3,731920 −40,991234'); return }
  ed.lat=p.lat; ed.lon=p.lon; ed.alt=null; ed.prec=null; ed.gpsFonte='manual';
  ed.cidade=''; ed.uf=''; ed.bairro=''; ed.cidadeManual=ed.bairroManual=false;
  await preencherLocal(ed);
  $('eBairro').value=ed.bairro||''; $('eCidade').value=ed.cidade||'';
  preencherDados(); previa();
};
$('eData').onchange=()=>{ const v=new Date($('eData').value); if(!isNaN(v)){ ed.data=v.getTime(); ed.dataFonte='manual'; preencherDados(); previa() } };

/* ---- corte ---- */
function entrarCorte(){ corteAtivo=true; corteTmp=ed.corte?Object.assign({},ed.corte):{x:0.05,y:0.05,w:0.9,h:0.9};
  $('eCorte').hidden=false; $('eCorteBotoes').hidden=false; $('eCortar').disabled=true; previa() }
function sairCorte(){ corteAtivo=false; $('eCorte').hidden=true; $('eCorteBotoes').hidden=true; $('eCortar').disabled=false }
$('eCortar').onclick=entrarCorte;
$('eCorteCancelar').onclick=()=>{ sairCorte(); previa() };
$('eCorteTudo').onclick=()=>{ corteTmp={x:0,y:0,w:1,h:1}; posicionarCorte() };
$('eCorteOk').onclick=()=>{ const c=corteTmp; ed.corte=(c.w>0.995&&c.h>0.995)?null:c; sairCorte(); previa() };
function posicionarCorte(){
  const cv=$('eCanvas'), pr=$('ePrevia').getBoundingClientRect(), r=cv.getBoundingClientRect();
  const o=$('eCorte'); o.style.left=(r.left-pr.left)+'px'; o.style.top=(r.top-pr.top)+'px'; o.style.width=r.width+'px'; o.style.height=r.height+'px';
  const c=corteTmp, b=$('eCaixa');
  b.style.left=(c.x*100)+'%'; b.style.top=(c.y*100)+'%'; b.style.width=(c.w*100)+'%'; b.style.height=(c.h*100)+'%';
}
(function(){
  let arr=null;
  const area=$('eCorte');
  area.addEventListener('pointerdown',e=>{
    const r=area.getBoundingClientRect(); const canto=e.target.dataset&&e.target.dataset.c;
    arr={canto: canto || (e.target.id==='eCaixa'?'mover':null), x0:e.clientX, y0:e.clientY, c0:Object.assign({},corteTmp), W:r.width, H:r.height};
    if(!arr.canto){ arr=null; return }
    area.setPointerCapture(e.pointerId); e.preventDefault();
  });
  area.addEventListener('pointermove',e=>{
    if(!arr) return;
    const dx=(e.clientX-arr.x0)/arr.W, dy=(e.clientY-arr.y0)/arr.H, c=Object.assign({},arr.c0), MIN=0.08;
    if(arr.canto==='mover'){ c.x=Math.min(Math.max(0,c.x+dx),1-c.w); c.y=Math.min(Math.max(0,c.y+dy),1-c.h) }
    else{
      if(arr.canto.includes('w')){ const nx=Math.min(Math.max(0,c.x+dx), c.x+c.w-MIN); c.w=c.w+(c.x-nx); c.x=nx }
      if(arr.canto.includes('e')){ c.w=Math.min(Math.max(MIN,c.w+dx),1-c.x) }
      if(arr.canto.includes('n')){ const ny=Math.min(Math.max(0,c.y+dy), c.y+c.h-MIN); c.h=c.h+(c.y-ny); c.y=ny }
      if(arr.canto.includes('s')){ c.h=Math.min(Math.max(MIN,c.h+dy),1-c.y) }
    }
    corteTmp=c; posicionarCorte();
  });
  const fim=()=>{ arr=null };
  area.addEventListener('pointerup',fim); area.addEventListener('pointercancel',fim);
  window.addEventListener('resize',()=>{ if(corteAtivo) posicionarCorte() });
})();

/* ---- salvar / cancelar / apagar ---- */
$('eCancelar').onclick=()=>history.back();
$('eVoltar').onclick=()=>history.back();
$('eSalvar').onclick=async()=>{
  if(corteAtivo){ $('eCorteOk').click() }
  const f=edOrig; if(!f) return;
  const manterUri=f.uri;
  Object.assign(f, ed); f.uri=manterUri; f.salva=false;
  guardarFotos();
  espera('Salvando…');
  await gerarMini(f);
  await salvarFoto(f);
  espera(); desenharLista(); aviso(f.salva?'Foto atualizada na galeria':'Alterações guardadas');
  history.back();
};
$('eApagar').onclick=async()=>{
  const f=edOrig; if(!f) return;
  if(!await perguntar('Tirar esta foto da lista?'+(f.salva?'<br><br>A cópia carimbada continua na galeria.':'<br><br><b>Ela ainda não foi salva</b> e vai ser perdida.'),'Tirar')) return;
  fotos=fotos.filter(x=>x.id!==f.id); if(A) A.apagarOriginal(f.arquivo); apagarMini(f.id); guardarFotos();
  history.back(); desenharLista();
};

/* ================= ajustes ================= */
function abrirAjustes(){
  $('aAuto').checked=cfg.auto; $('aBairro').checked=cfg.bairro; $('aUf').checked=cfg.uf; $('aAlt').checked=cfg.alt; $('aPrec').checked=cfg.prec;
  $('aPos').value=cfg.pos; $('aTam').value=cfg.tam; $('aLado').value=cfg.lado;
  $('ajustes').hidden=false; history.pushState({aj:1},'');
}
$('bAjustes').onclick=abrirAjustes;
$('aVoltar').onclick=()=>history.back();
[['aAuto','auto'],['aBairro','bairro'],['aUf','uf'],['aAlt','alt'],['aPrec','prec']].forEach(([id,k])=>$(id).onchange=async()=>{ cfg[k]=$(id).checked; guardarCfg(); if(k!=='auto') await refazerMinis() });
[['aPos','pos'],['aTam','tam'],['aLado','lado']].forEach(([id,k])=>$(id).onchange=()=>{ cfg[k]=$(id).value; guardarCfg() });
async function refazerMinis(){ for(const f of fotos){ f.salva=false; await gerarMini(f) } guardarFotos(); desenharLista() }

/* ================= início ================= */
desenharLista();
