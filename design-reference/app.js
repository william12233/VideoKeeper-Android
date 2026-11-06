/* VideoKeeper Web v1.1 — 全部影片僅在瀏覽器本機處理。無第三方函式庫。 */
'use strict';
(() => {
  const $ = id => document.getElementById(id);
  const s = {items:[], pairs:[], outputs:[], selectedId:null, busy:false, cancel:false, job:'', toastTimer:null, activeConversion:null};
  let idCounter=0;
  const esc = x => String(x).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const fmtSize = n => n>=1024**3 ? (n/1024**3).toFixed(2)+' GB' : n>=1024**2 ? (n/1024**2).toFixed(1)+' MB' : (n/1024).toFixed(0)+' KB';
  const fmtTime = n => Number.isFinite(n) ? `${Math.floor(n/60)}:${String(Math.floor(n%60)).padStart(2,'0')}` : '未知';
  const clamp=(x,a,b)=>Math.max(a,Math.min(b,x));
  const median=xs=>{const a=[...xs].sort((x,y)=>x-y);return a.length?(a[Math.floor((a.length-1)/2)]+a[Math.floor(a.length/2)])/2:0;};
  const signal = (msg,timeout=3200)=>{const el=$('toast');el.textContent=msg;el.classList.add('show');clearTimeout(s.toastTimer);s.toastTimer=setTimeout(()=>el.classList.remove('show'),timeout);};
  const checkCancel=()=>{if(s.cancel)throw new Error('使用者已停止工作');};
  const itemBy=id=>s.items.find(x=>x.id===Number(id));

  function addFiles(fileList){
    if(s.busy){signal('請等待目前的工作完成');return;}
    const files=Array.from(fileList||[]).filter(f=>/\.(mp4|mov|m4v|webm|mkv|avi|3gp|ts)$/i.test(f.name)||f.type.startsWith('video/'));
    let added=0;
    for(const file of files){
      const path=file.webkitRelativePath||file.name;
      if(s.items.some(x=>x.path===path&&x.file.size===file.size&&x.file.lastModified===file.lastModified))continue;
      s.items.push({id:++idCounter,file,path,meta:null,crop:null,analysisError:null,hashError:null,hashes:null,poster:null,checked:true});added++;
    }
    if(!files.length)signal('沒有找到支援的影片檔案');else signal(`已加入 ${added} 支影片`);
    s.pairs=[];$('exportCsv').disabled=true;
    $('dupeResults').innerHTML='<div class="empty-state"><span>◎</span><strong>待重新掃描</strong><p>檔案清單已變動，請重新執行內容比對。</p></div>';
    $('dupeSummary').textContent='尚未掃描';
    $('workspace').hidden=s.items.length===0;$('fileSummary').hidden=s.items.length===0;
    $('countFiles').textContent=s.items.length;$('totalSize').textContent=fmtSize(s.items.reduce((a,x)=>a+x.file.size,0));
    renderCropList();
  }
  $('fileInput').addEventListener('change',e=>{addFiles(e.target.files);e.target.value='';});
  $('folderInput').addEventListener('change',e=>{addFiles(e.target.files);e.target.value='';});
  for(const name of ['dragover','dragenter'])$('dropzone').addEventListener(name,e=>{e.preventDefault();$('dropzone').classList.add('dragover');});
  for(const name of ['dragleave','drop'])$('dropzone').addEventListener(name,e=>{e.preventDefault();$('dropzone').classList.remove('dragover');});
  $('dropzone').addEventListener('drop',e=>addFiles(e.dataTransfer?.files));
  $('clearFiles').addEventListener('click',()=>{if(s.busy)return signal('請先停止目前工作');
    s.items=[];s.pairs=[];s.selectedId=null;clearOutputBuffers();$('fileSummary').hidden=true;$('workspace').hidden=true;$('cropEditor').hidden=true;signal('已清空清單；原始檔案未受影響');
  });
  function tab(name){
    const crop=name==='crop';$('tabCrop').classList.toggle('is-active',crop);$('tabDupe').classList.toggle('is-active',!crop);
    $('tabCrop').setAttribute('aria-selected',String(crop));$('tabDupe').setAttribute('aria-selected',String(!crop));
    $('panelCrop').hidden=!crop;$('panelDupe').hidden=crop;
  }
  $('tabCrop').addEventListener('click',()=>tab('crop'));
  $('tabDupe').addEventListener('click',()=>tab('dupe'));

  function fileStatus(x){
    if(x.analysisError)return '<span class="pill bad">無法解碼</span>';
    if(!x.crop)return '<span class="pill">未分析</span>';
    if(x.crop.confidence==='high')return `<span class="pill good">左右黑邊 ${Math.round(x.crop.left+x.crop.right)}%</span>`;
    if(x.crop.confidence==='manual')return '<span class="pill warn">手動調整</span>';
    return '<span class="pill warn">未偵測到穩定黑邊</span>';
  }
  function renderCropList(){
    $('cropSummary').textContent=`${s.items.length} 支影片・${s.items.filter(x=>x.crop?.confidence==='high').length} 支建議裁切`;
    $('cropList').innerHTML=s.items.map(x=>`<div class="file-row ${s.selectedId===x.id?'is-selected':''}" data-id="${x.id}">
      <input type="checkbox" class="file-check" data-check="${x.id}" ${x.checked?'checked':''} aria-label="勾選 ${esc(x.file.name)}">
      <div class="file-main"><div class="filename" title="${esc(x.path)}">${esc(x.path)}</div><div class="file-meta">${x.meta?`${x.meta.width}×${x.meta.height} · ${fmtTime(x.meta.duration)}`:'尚未讀取資訊'} · ${fmtSize(x.file.size)}</div></div>
      <div class="file-actions">${fileStatus(x)}<button class="tiny-btn" data-edit="${x.id}" type="button">預覽／調整</button></div></div>`).join('');
    $('cropList').querySelectorAll('[data-check]').forEach(el=>el.addEventListener('change',e=>{const x=itemBy(e.target.dataset.check);if(x)x.checked=e.target.checked;}));
    $('cropList').querySelectorAll('[data-edit]').forEach(el=>el.addEventListener('click',()=>selectEditor(Number(el.dataset.edit))));
  }
  function paintProgress(title,ratio,detail){$('progressPanel').hidden=false;$('progressTitle').textContent=title;
    $('progressPercent').textContent=Math.round(clamp(ratio,0,1)*100)+'%';$('progressBar').style.width=clamp(ratio,0,1)*100+'%';$('progressDetail').textContent=detail;
  }
  function setBusy(on){s.busy=on;['analyzeCrop','analyzeDupe','exportSelected','clearFiles','fileInput','folderInput'].forEach(id=>$(id).disabled=on);
    $('cancelJob').hidden=!on;$('clearFiles').disabled=on;
    if(!on){$('progressPanel').hidden=true;s.cancel=false;}
  }
  async function task(name,action){if(s.busy)return signal('已有一項工作正在進行');s.cancel=false;s.job=name;setBusy(true);
    try{await action();if(!s.cancel)signal('工作完成');}
    catch(e){console.error(e);signal(e.message||'處理失敗',5500);}
    finally{setBusy(false);renderCropList();}
  }
  $('cancelJob').addEventListener('click',()=>{s.cancel=true;if(s.activeConversion)void s.activeConversion.cancel().catch(()=>{});signal('正在結束目前處理…');});

  function openVideo(item){
    return new Promise((resolve,reject)=>{
      const v=document.createElement('video');v.preload='auto';v.playsInline=true;v.setAttribute('playsinline','');
      v.style.cssText='position:fixed;left:-9999px;top:0;width:1px;height:1px;pointer-events:none;opacity:.01';
      const url=URL.createObjectURL(item.file);let done=false;
      const clean=()=>{v.onloadedmetadata=null;v.onerror=null;clearTimeout(timeout);};
      const fail=err=>{if(done)return;done=true;clean();dispose();reject(new Error(`${item.file.name}：${err}`));};
      const dispose=()=>{try{v.pause();v.removeAttribute('src');v.load();v.remove();URL.revokeObjectURL(url);}catch{}};
      const timeout=setTimeout(()=>fail('影片讀取逾時或格式不支援'),20000);
      v.onloadedmetadata=()=>{if(done)return;done=true;clean();if(!v.videoWidth||!v.videoHeight){dispose();return reject(new Error(`${item.file.name}：無法取得影片尺寸`));}
        resolve({video:v,dispose});};
      v.onerror=()=>fail('此瀏覽器不支援這支影片的解碼格式');
      document.body.appendChild(v);v.src=url;v.load();
    });
  }
  function seek(video,seconds){
    return new Promise((resolve,reject)=>{
      if(!Number.isFinite(video.duration)||video.duration===0)return reject(new Error('不支援無固定長度的影片'));
      const time=clamp(seconds,0,Math.max(0,video.duration-.045));
      if(Math.abs(video.currentTime-time)<.025 && video.readyState>=2)return resolve();
      let done=false;
      const clear=()=>{video.removeEventListener('seeked',success);video.removeEventListener('error',fail);clearTimeout(t);};
      const success=()=>{if(done)return;done=true;clear();requestAnimationFrame(()=>resolve());};
      const fail=()=>{if(done)return;done=true;clear();reject(new Error('畫格定位失敗，可能不支援此編碼'));};
      const t=setTimeout(fail,13000);
      video.addEventListener('seeked',success,{once:true});video.addEventListener('error',fail,{once:true});
      try{video.currentTime=time;}catch{fail();}
    });
  }
  function createFrameCanvas(w,h,maxW=500,maxH=320){const scale=Math.min(1,maxW/w,maxH/h), c=document.createElement('canvas');
    c.width=Math.max(2,Math.round(w*scale));c.height=Math.max(2,Math.round(h*scale));return c;
  }
  function capture(video,ctx){ctx.drawImage(video,0,0,ctx.canvas.width,ctx.canvas.height);return ctx.getImageData(0,0,ctx.canvas.width,ctx.canvas.height);}

  // 多個時間點的左右黑邊檢測。必須同時符合黑色連續區、左右近似對稱、內部有畫面與直立比例。
  function detectMargins(img){
    const {data,width:w,height:h}=img;
    if(w<30||h<30)return null;
    const dark=new Uint8Array(w);const y1=Math.floor(h*.14),y2=Math.floor(h*.86),stride=Math.max(1,Math.floor((y2-y1)/76));
    for(let x=0;x<w;x++){
      let samples=0,count=0;
      for(let y=y1;y<y2;y+=stride){const i=(y*w+x)*4,r=data[i],g=data[i+1],b=data[i+2];
        if(Math.max(r,g,b)<=42 && (r*.2126+g*.7152+b*.0722)<30)count++;samples++;}
      dark[x]=(count/samples)>=.935?1:0;
    }
    let left=0,right=0;
    // 小型浮水印或編碼雜點可有少數缺口，但不能連續跨越明亮內容。
    let misses=0;
    for(let i=0;i<Math.floor(w*.44);i++){
      if(dark[i]){left=i+1;misses=0;}else if(++misses>=3)break;
    }
    misses=0;
    for(let i=w-1;i>=Math.ceil(w*.56);i--){
      if(dark[i]){right=w-i;misses=0;}else if(++misses>=3)break;
    }
    const min=Math.min(left,right),max=Math.max(left,right);
    if(min<w*.075||max-min>w*.07)return null;
    const activeW=w-left-right;
    if(activeW<w*.11||activeW/h>.94)return null;
    const inside=(start,end)=>{
      start=Math.max(0,Math.floor(start));end=Math.min(w,Math.ceil(end));
      let bright=0,total=0;const xs=Math.max(1,Math.floor((end-start)/11));
      for(let x=start;x<end;x+=xs)for(let y=y1;y<y2;y+=stride){const i=(y*w+x)*4;
        if(data[i]*.2126+data[i+1]*.7152+data[i+2]*.0722>42)bright++;total++;}
      return total?bright/total:0;
    };
    if(inside(left+Math.max(2,activeW*.08),left+activeW*.92)<.12)return null;
    return {left,right,width:w,height:h};
  }
  function buildCrop(margins,w,h){
    if(margins.length<3)return {left:0,right:0,confidence:'none',autoLeft:0,autoRight:0};
    const l=median(margins.map(x=>x.left)),r=median(margins.map(x=>x.right));
    const spread=Math.max(...margins.map(x=>Math.max(Math.abs(x.left-l),Math.abs(x.right-r))));
    if(spread>w*.026)return {left:0,right:0,confidence:'none',autoLeft:0,autoRight:0};
    const left=clamp((l-.5)/w*100,0,44),right=clamp((r-.5)/w*100,0,44);
    return {left,right,confidence:'high',autoLeft:left,autoRight:right};
  }
  async function ensureMeta(item){if(item.meta)return;
    const {video,dispose}=await openVideo(item);try{item.meta={width:video.videoWidth,height:video.videoHeight,duration:video.duration};}
    finally{dispose();}
  }
  async function analyzeCrop(item,report){
    const {video,dispose}=await openVideo(item);
    try{
      const w=video.videoWidth,h=video.videoHeight,dur=video.duration;
      item.meta={width:w,height:h,duration:dur};const c=createFrameCanvas(w,h);const cx=c.getContext('2d',{willReadFrequently:true});
      const times=[.10,.27,.5,.73,.9];const margins=[];
      for(let i=0;i<times.length;i++){
        checkCancel();await seek(video,Math.max(.03,dur*times[i]));const frame=capture(video,cx);
        if(i===2)item.poster=c.toDataURL('image/jpeg',.76);
        const result=detectMargins(frame);if(result)margins.push(result);
        report((i+1)/times.length);
      }
      // frame -> source percent identical under uniform scaling
      const auto=buildCrop(margins,c.width,c.height);
      if(item.crop?.confidence==='manual'){
        item.crop.autoLeft=auto.left;item.crop.autoRight=auto.right;
      } else item.crop=auto;
      item.analysisError=null;
    }finally{dispose();}
  }
  $('analyzeCrop').addEventListener('click',()=>task('crop',async()=>{
    let done=0;for(const item of s.items){checkCancel();
      try{await analyzeCrop(item,p=>paintProgress('分析左右黑邊',(done+p)/s.items.length,`${item.file.name} · 第 ${Math.ceil(p*5)} / 5 個時間點`));}
      catch(e){if(s.cancel)throw e;item.analysisError=e.message;item.crop={left:0,right:0,autoLeft:0,autoRight:0,confidence:'none'};console.warn(e);}
      done++;renderCropList();}
    const good=s.items.filter(x=>x.crop?.confidence==='high').length;
    signal(`分析完成：${good} 支偵測到穩定左右黑邊`,4500);
    if(s.selectedId){const selected=itemBy(s.selectedId);if(selected&&selected.poster)await drawEditor(selected);}
  }));

  async function selectEditor(id){
    if(s.busy)return signal('工作執行中無法開啟預覽');
    const item=itemBy(id);if(!item)return;
    try{
      if(!item.poster){
        const {video,dispose}=await openVideo(item);try{item.meta={width:video.videoWidth,height:video.videoHeight,duration:video.duration};
          await seek(video,video.duration*.5);const c=createFrameCanvas(video.videoWidth,video.videoHeight);c.getContext('2d').drawImage(video,0,0,c.width,c.height);item.poster=c.toDataURL('image/jpeg',.76);
        }finally{dispose();}
      }
      if(!item.crop)item.crop={left:0,right:0,autoLeft:0,autoRight:0,confidence:'none'};
      s.selectedId=id;$('cropEditor').hidden=false;$('editorName').textContent=item.path;
      $('leftRange').value=item.crop.left;$('rightRange').value=item.crop.right;
      renderCropList();await drawEditor(item);$('cropEditor').scrollIntoView({block:'nearest',behavior:'smooth'});
    }catch(e){signal(e.message,5000);}
  }
  function loadImage(url){return new Promise((res,rej)=>{const im=new Image();im.onload=()=>res(im);im.onerror=rej;im.src=url;});}
  async function drawEditor(item){
    if(!item.poster||s.selectedId!==item.id)return;
    const img=await loadImage(item.poster);if(s.selectedId!==item.id)return;
    const left=clamp(item.crop.left,0,44.9),right=clamp(item.crop.right,0,44.9);
    const before=$('beforeCanvas'),after=$('afterCanvas');before.width=img.width;before.height=img.height;
    const b=before.getContext('2d');b.drawImage(img,0,0);b.fillStyle='#020710b8';b.fillRect(0,0,img.width*left/100,img.height);b.fillRect(img.width*(1-right/100),0,img.width*right/100,img.height);
    b.strokeStyle='#8bdaef';b.lineWidth=2;b.strokeRect(img.width*left/100,1,img.width*(1-(left+right)/100),img.height-2);
    const cropLeft=img.width*left/100,cropWidth=img.width*(1-(left+right)/100);after.width=Math.max(1,Math.round(cropWidth));after.height=img.height;
    after.getContext('2d').drawImage(img,cropLeft,0,cropWidth,img.height,0,0,after.width,after.height);
    $('leftValue').textContent=left.toFixed(1)+'%';$('rightValue').textContent=right.toFixed(1)+'%';
    const actual=Math.max(2,Math.round(item.meta.width*(1-(left+right)/100)/2)*2);
    $('outputResolution').textContent=`預估裁切區：${actual} × ${item.meta.height}（輸出可能縮放）`;
  }
  for(const [key,range] of [['left','leftRange'],['right','rightRange']])$(range).addEventListener('input',()=>{
    const x=itemBy(s.selectedId);if(!x)return;
    x.crop[key]=Number($(range).value);x.crop.confidence='manual';x.hashes=null;
    drawEditor(x);
  });
  for(const key of ['leftRange','rightRange'])$(key).addEventListener('change',renderCropList);
  $('resetCrop').addEventListener('click',()=>{
    const x=itemBy(s.selectedId);if(!x)return;
    x.crop.left=x.crop.autoLeft||0;x.crop.right=x.crop.autoRight||0;
    x.crop.confidence=(x.crop.left+x.crop.right)>.1?'high':'none';x.hashes=null;
    $('leftRange').value=x.crop.left;$('rightRange').value=x.crop.right;drawEditor(x);renderCropList();
  });

  // pHash 16x16 -> 8x8 低頻 DCT，dHash 9x8 -> 64 bits；另有亮度變異檢查以排除純黑畫面。
  const cosTable=Array.from({length:8},(_,u)=>Array.from({length:16},(_,x)=>Math.cos((2*x+1)*u*Math.PI/32)));
  const sigC=document.createElement('canvas'),sigCtx=sigC.getContext('2d',{willReadFrequently:true});
  function lumSample(video,x,y,width,height,outW,outH){sigC.width=outW;sigC.height=outH;
    sigCtx.drawImage(video,x,y,width,height,0,0,outW,outH);
    const rgba=sigCtx.getImageData(0,0,outW,outH).data;const gray=new Float32Array(outW*outH);
    for(let i=0,j=0;i<rgba.length;i+=4,j++)gray[j]=rgba[i]*.299+rgba[i+1]*.587+rgba[i+2]*.114;
    return gray;
  }
  function getSignature(video,crop){
    const w=video.videoWidth,h=video.videoHeight;
    const x=Math.floor(w*(crop?.left||0)/100),right=Math.floor(w*(crop?.right||0)/100),cw=Math.max(2,w-x-right);
    const gray=lumSample(video,x,0,cw,h,16,16);
    let mean=0,varSum=0;for(const v of gray)mean+=v;mean/=gray.length;
    for(const v of gray)varSum+=(v-mean)**2;
    const variance=Math.sqrt(varSum/gray.length);
    const coeff=[];
    for(let u=0;u<8;u++)for(let v=0;v<8;v++){
      let sum=0;for(let yy=0;yy<16;yy++)for(let xx=0;xx<16;xx++)sum+=gray[yy*16+xx]*cosTable[u][xx]*cosTable[v][yy];
      coeff.push(sum);
    }
    const m=median(coeff.slice(1));const p=coeff.map((v,i)=>i===0?'0':(v>m?'1':'0')).join('');
    const dgray=lumSample(video,x,0,cw,h,9,8);let d='';
    for(let yy=0;yy<8;yy++)for(let xx=0;xx<8;xx++)d+=(dgray[yy*9+xx]>dgray[yy*9+xx+1]?'1':'0');
    return {p,d,variance};
  }
  function sampleCrop(video){
    const c=createFrameCanvas(video.videoWidth,video.videoHeight,320,220);
    const cx=c.getContext('2d',{willReadFrequently:true}),m=detectMargins(capture(video,cx));
    if(!m)return null;
    return {left:Math.max(0,(m.left-1)/c.width*100),right:Math.max(0,(m.right-1)/c.width*100)};
  }
  async function analyzeHashes(item,report){
    const {video,dispose}=await openVideo(item);
    try{
      item.meta={width:video.videoWidth,height:video.videoHeight,duration:video.duration};
      const hashes=[];const count=10;
      for(let i=0;i<count;i++){
        checkCancel();await seek(video,video.duration*((i+.65)/(count+.3)));
        const crop=(item.crop?.confidence==='high'||item.crop?.confidence==='manual')?item.crop:sampleCrop(video);
        hashes.push(getSignature(video,crop));report((i+1)/count);
      }
      item.hashes=hashes;item.hashError=null;
    }finally{dispose();}
  }
  function bitDist(a,b){let n=0;for(let i=0;i<a.length;i++)if(a[i]!==b[i])n++;return n/a.length;}
  function frameDist(a,b){
    if(a.variance<8&&b.variance<8)return null;
    if((a.variance<8)!==(b.variance<8))return 1;
    return bitDist(a.p,b.p)*.62+bitDist(a.d,b.d)*.38;
  }
  function similarity(a,b){
    const d1=a.meta.duration,d2=b.meta.duration;
    if(!Number.isFinite(d1)||!Number.isFinite(d2)||Math.abs(d1-d2)>Math.max(4,Math.min(d1,d2)*.18))return null;
    let best=Infinity,matched=0,used=0;
    for(let shift=-2;shift<=2;shift++){
      const ds=[];let strong=0;
      for(let i=0;i<a.hashes.length;i++){
        const j=i+shift;if(j<0||j>=b.hashes.length)continue;
        const dist=frameDist(a.hashes[i],b.hashes[j]);if(dist===null)continue;
        ds.push(dist);if(dist<.24)strong++;
      }
      if(ds.length<4)continue;
      const sorted=[...ds].sort((x,y)=>x-y);
      const trimmed=sorted.slice(0,Math.max(4,Math.ceil(sorted.length*.85)));
      const avg=trimmed.reduce((x,y)=>x+y,0)/trimmed.length;
      // 需要跨越多個時間點保持相似；僅少數相同靜態畫面不構成重複。
      const score=avg+(strong/ds.length<.58?.13:0);
      if(score<best){best=score;matched=strong;used=ds.length;}
    }
    if(!Number.isFinite(best)||matched<4||matched/used<.58)return null;
    const confidence=Math.round(clamp(100*(1-best*1.25),0,100));
    return confidence>=70?{confidence,matched,used}:null;
  }
  function qualityScore(x){const px=x.meta.width*x.meta.height,rate=(x.file.size*8)/Math.max(x.meta.duration,1);
    return Math.log2(px+1)*.6+Math.log2(Math.max(rate,1))*.4;
  }
  function makePairs(){const pairs=[];
    for(let i=0;i<s.items.length;i++)for(let j=i+1;j<s.items.length;j++){
      const a=s.items[i],b=s.items[j];if(!a.hashes||!b.hashes)continue;
      const sim=similarity(a,b);if(sim)pairs.push({a,b,...sim});
    }
    pairs.sort((x,y)=>y.confidence-x.confidence);return pairs;
  }
  function renderPairs(){
    const pairs=s.pairs;
    $('dupeSummary').textContent=pairs.length?`找到 ${pairs.filter(p=>p.confidence>=85).length} 組高度相似、${pairs.filter(p=>p.confidence<85).length} 組待確認`:`完成比對 ${s.items.filter(x=>x.hashes).length} 支影片，未找到達門檻的相似組合`;
    $('exportCsv').disabled=!pairs.length;
    if(!pairs.length){$('dupeResults').innerHTML='<div class="empty-state"><span>✓</span><strong>沒有找到明顯重複</strong><p>並不代表完全沒有重複，短片或大幅剪輯影片可能無法辨識。</p></div>';return;}
    $('dupeResults').innerHTML=pairs.map((p,i)=>{
      const best=qualityScore(p.a)>qualityScore(p.b)?p.a:p.b;
      return `<div class="dupe-card"><div class="dupe-card-title"><strong>相似組合 ${i+1}</strong><span class="pill ${p.confidence>=85?'good':'warn'}">${p.confidence}% · ${p.confidence>=85?'高度相似':'待確認'}</span></div>
      <div class="dupe-files">${[p.a,p.b].map(x=>`<div class="dupe-file"><strong title="${esc(x.path)}">${esc(x.path)}</strong><small>${x.meta.width} × ${x.meta.height} · ${fmtTime(x.meta.duration)} · ${fmtSize(x.file.size)}${x===best?' · 品質保留候選':''}</small></div>`).join('')}</div>
      <p class="dupe-tip">${p.matched}/${p.used} 個有效時間點相符。品質保留候選只根據解析度與平均位元率估算，請先確認實際畫質及音訊。</p></div>`;
    }).join('');
  }
  $('analyzeDupe').addEventListener('click',()=>task('duplicate',async()=>{
    if(s.items.length<2)return signal('至少需要兩支影片才能比對');
    let done=0;for(const x of s.items){checkCancel();
      if(!x.hashes){try{await analyzeHashes(x,p=>paintProgress('掃描影片內容',(done+p)/s.items.length,`${x.file.name} · 畫面指紋 ${Math.ceil(p*10)} / 10`));}
        catch(e){if(s.cancel)throw e;x.hashError=e.message;console.warn(e);}}
      done++;paintProgress('掃描影片內容',done/s.items.length,`${done}/${s.items.length} 支影片已處理`);
    }
    s.pairs=makePairs();renderPairs();signal(`比對完成：${s.pairs.length} 組相似影片`,4500);
  }));
  const csvCell=text=>{
    let value=String(text??'');if(/^[\s]*[=+@-]/.test(value))value="'"+value;
    return '"'+value.replace(/"/g,'""')+'"';
  };
  function downloadBlob(blob,name){const url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download=name;
    a.style.display='none';document.body.appendChild(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),30000);
  }
  $('exportCsv').addEventListener('click',()=>{
    if(!s.pairs.length)return;
    const rows=[['影片 A','影片 B','相似度 (%)','判斷','匹配畫格','影片 A 尺寸','影片 B 尺寸','建議保留候選']];
    for(const p of s.pairs)rows.push([p.a.path,p.b.path,p.confidence,p.confidence>=85?'高度相似':'待確認',`${p.matched}/${p.used}`,`${p.a.meta.width}x${p.a.meta.height}`,`${p.b.meta.width}x${p.b.meta.height}`,qualityScore(p.a)>=qualityScore(p.b)?p.a.path:p.b.path]);
    const csv='\uFEFF'+rows.map(r=>r.map(csvCell).join(',')).join('\r\n');downloadBlob(new Blob([csv],{type:'text/csv;charset=utf-8'}),'影片管家_相似影片報告.csv');
  });

  // 高速模式：利用 Mediabunny 的 WebCodecs 解碼/編碼及 MP4 mux，按影格處理，不依賴即時播放。
  // 第三方程式僅在選擇匯出時載入；不傳送影片檔案或其他資料至 CDN。
  const MEDIABUNNY_URL='https://cdn.jsdelivr.net/npm/mediabunny@1.65.2/dist/bundles/mediabunny.min.cjs';
  let mediabunnyPromise=null;
  const fastSupported=()=>!!(window.isSecureContext && window.VideoEncoder && window.VideoDecoder);
  function updateEngineStatus(message,kind=''){
    const el=$('engineStatus');el.textContent=message;el.classList.toggle('warn',kind==='warn');el.classList.toggle('ready',kind==='ready');
  }
  function refreshEngineStatus(){
    if($('engineSelect').value==='legacy')return updateEngineStatus('相容模式：每支影片約需原長度的時間，支援範圍較廣。','warn');
    if(fastSupported())updateEngineStatus('⚡ 支援高速轉碼。第一次匯出需要連網載入引擎，之後會優先使用手機支援的硬體編解碼。','ready');
    else updateEngineStatus('此頁面的執行環境沒有開放 WebCodecs，將使用即時錄製。建議將完整網站部署到 HTTPS（例如 GitHub Pages）。','warn');
  }
  $('engineSelect').addEventListener('change',refreshEngineStatus);refreshEngineStatus();
  function loadMediabunny(){
    if(window.Mediabunny?.Conversion)return Promise.resolve(window.Mediabunny);
    if(mediabunnyPromise)return mediabunnyPromise;
    mediabunnyPromise=new Promise((resolve,reject)=>{
      const script=document.createElement('script');let done=false;
      const timeout=setTimeout(()=>fail(new Error('高速轉檔引擎載入逾時，請檢查手機網路')),14000);
      function fail(error){if(done)return;done=true;clearTimeout(timeout);script.remove();reject(error);}
      script.onload=()=>{if(done)return;done=true;clearTimeout(timeout);
        if(window.Mediabunny?.Conversion)resolve(window.Mediabunny);
        else reject(new Error('載入的引擎缺少所需功能'));
      };
      script.onerror=()=>fail(new Error('無法載入高速轉檔引擎（請檢查網路或廣告攔截器）'));
      script.src=MEDIABUNNY_URL;script.async=true;document.head.appendChild(script);
    }).catch(e=>{mediabunnyPromise=null;throw e;});
    return mediabunnyPromise;
  }
  function dimensionsFor(videoWidth,videoHeight,crop,quality){
    const left=clamp(crop.left,0,44.9)/100,right=clamp(crop.right,0,44.9)/100;
    const sx=Math.round(videoWidth*left);
    const sw=Math.max(2,Math.floor((videoWidth-sx-Math.round(videoWidth*right))/2)*2);
    const limit=quality==='max'?3840:1920;const scale=Math.min(1,limit/videoHeight);
    const outW=Math.max(2,Math.round(sw*scale/2)*2),outH=Math.max(2,Math.round(videoHeight*scale/2)*2);
    const base=quality==='max'?12_000_000:quality==='medium'?3_000_000:6_000_000;
    const bitrate=Math.max(1_100_000,Math.min(base,Math.round(base*outW*outH/(1080*1920))));
    return {sx,sw,outW,outH,bitrate};
  }
  async function exportOneFast(item,onProgress,mb){
    const started=performance.now(),quality=$('qualitySelect').value;
    const input=new mb.Input({source:new mb.BlobSource(item.file),formats:mb.ALL_FORMATS});
    let conversion=null;
    try{
      const track=await input.getPrimaryVideoTrack();
      if(!track)throw new Error('無可辨識的影片軌道');
      const [width,height]=await Promise.all([track.getDisplayWidth(),track.getDisplayHeight()]);
      if(!width||!height)throw new Error('無法取得影片尺寸');
      const d=dimensionsFor(width,height,item.crop,quality);
      const sourceAudio=await input.getPrimaryAudioTrack();
      const output=new mb.Output({format:new mb.Mp4OutputFormat(),target:new mb.BufferTarget()});
      conversion=await mb.Conversion.init({
        input,output,tracks:'primary',showWarnings:false,
        video:{crop:{left:d.sx,top:0,width:d.sw,height},width:d.outW,height:d.outH,
          fit:'fill',codec:'avc',quality:new mb.Quality({bitrate:d.bitrate}),
          hardwareAcceleration:'prefer-hardware',allowTransformationMetadata:false},
        audio:{codec:'aac'}
      });
      if(!conversion.isValid||!conversion.utilizedTracks.includes(track)){
        const reasons=conversion.discardedTracks.map(t=>t.reason).join('、');
        throw new Error('無法在此手機進行高速轉碼'+(reasons?'：'+reasons:''));
      }
      if(sourceAudio&&!conversion.utilizedTracks.includes(sourceAudio)){
        throw new Error('高速模式無法保留這支影片的音訊；請改用相容模式');
      }
      checkCancel();
      conversion.onProgress=p=>onProgress(Math.min(.99,p));
      s.activeConversion=conversion;
      await conversion.execute();
      checkCancel();
      const buffer=output.target.buffer;
      if(!buffer||buffer.byteLength<256)throw new Error('高速模式輸出為空');
      onProgress(1);
      const blob=new Blob([buffer],{type:'video/mp4'});
      return {blob,ext:'mp4',width:d.outW,height:d.outH,hasAudio:!!sourceAudio,
        filename:item.file.name.replace(/\.[^.]+$/,'')+'_cropped.mp4',
        speed:item.meta?.duration?item.meta.duration/((performance.now()-started)/1000):null,engine:'fast'};
    }finally{
      if(s.activeConversion===conversion)s.activeConversion=null;
      // WebCodecs 與 BlobSource 在 input.dispose 時釋放，避免批次處理累積解碼器。
      input.dispose();
    }
  }
  async function exportOneAuto(item,onProgress){
    if($('engineSelect').value==='auto'&&fastSupported()){
      paintProgress('正在準備高速匯出',0,`${item.file.name} · 載入轉檔引擎`);
      let mb;
      try{mb=await loadMediabunny();checkCancel();}
      catch(e){if(s.cancel)throw e;
        console.warn('高速引擎載入失敗，切換到相容模式',e);
        signal(`高速引擎不可用：${e.message}。改用相容模式`,6000);
        updateEngineStatus('高速引擎載入失敗，已使用相容模式。請檢查網路連線。','warn');
      }
      if(mb)return exportOneFast(item,onProgress,mb);
    }
    return exportOne(item,onProgress);
  }

  function recordType(){
    if(!window.MediaRecorder)throw new Error('這個瀏覽器不支援影片錄製，建議使用最新版 Chrome');
    for(const mime of ['video/mp4;codecs="avc1.42E01E,mp4a.40.2"','video/mp4','video/webm;codecs=vp9,opus','video/webm;codecs=vp8,opus','video/webm']){
      if(MediaRecorder.isTypeSupported(mime))return {mime,extension:mime.startsWith('video/mp4')?'mp4':'webm'};
    }
    throw new Error('瀏覽器不支援 MP4 或 WebM 編碼');
  }
  async function exportOne(item,onProgress){
    if(!item.crop||item.crop.left+item.crop.right<.5)return null;
    const {video,dispose}=await openVideo(item);let recorder=null,stream=null,context=null,ticker=null,wakeLock=null,cleanupVideoFrame=null;
    try{
      const quality=$('qualitySelect').value;
      const {sx,sw,outW,outH,bitrate:actualBitrate}=dimensionsFor(video.videoWidth,video.videoHeight,item.crop,quality);
      const canvas=document.createElement('canvas');canvas.width=outW;canvas.height=outH;
      const ctx=canvas.getContext('2d',{alpha:false});
      const paint=()=>{try{ctx.drawImage(video,sx,0,sw,video.videoHeight,0,0,outW,outH);}catch{}};
      await seek(video,0.03);paint();
      const fmt=recordType();stream=canvas.captureStream(30);
      if(!stream?.getVideoTracks().length)throw new Error('瀏覽器無法擷取裁切後的影格');
      // 使用 Web Audio 將來源影片的聲音導入錄影資料流，不發送到外部伺服器。
      try{
        const AudioCtx=window.AudioContext||window.webkitAudioContext;
        if(AudioCtx){context=new AudioCtx();const src=context.createMediaElementSource(video),dest=context.createMediaStreamDestination();src.connect(dest);
          for(const t of dest.stream.getAudioTracks())stream.addTrack(t);
          await context.resume();}
      }catch(e){console.warn('音訊通道不可用',e);if(context){await context.close().catch(()=>{});context=null;}}

      recorder=new MediaRecorder(stream,{mimeType:fmt.mime,videoBitsPerSecond:actualBitrate,audioBitsPerSecond:128000});
      const blobs=[];let mediaError=null,ended=false;
      const stopped=new Promise((resolve,reject)=>{
        recorder.ondataavailable=e=>{if(e.data?.size)blobs.push(e.data);};
        recorder.onerror=e=>{mediaError=e.error||new Error('錄影編碼失敗');};
        recorder.onstop=()=>mediaError?reject(mediaError):resolve();
      });
      if('wakeLock' in navigator){try{wakeLock=await navigator.wakeLock.request('screen');}catch{}}
      // 定時重繪搭配影片影格回呼，避免手機掉幀時停在舊畫面。
      if(video.requestVideoFrameCallback){
        let active=true;
        const frame=()=>{if(!active)return;paint();video.requestVideoFrameCallback(frame);};
        video.requestVideoFrameCallback(frame);cleanupVideoFrame=()=>{active=false;};
      }else ticker=setInterval(paint,1000/30);
      recorder.start(1500);
      try{video.muted=false;await video.play();}
      catch(e){video.muted=true;try{await video.play();}catch{throw new Error('手機瀏覽器阻擋影片播放，請保持頁面在前景再重試');}
        console.warn('影片以靜音播放模式啟動，原音訊輸出可能受瀏覽器限制',e);}
      const duration=video.duration;
      while(!ended){
        await new Promise(r=>setTimeout(r,320));
        if(s.cancel)throw new Error('已停止匯出');
        if(mediaError)throw mediaError;
        if(video.ended||video.currentTime>=duration-.045){ended=true;break;}
        if(video.paused)throw new Error('影片播放被中斷，請保持網頁在前景');
        onProgress(clamp(video.currentTime/duration,0,.99));
      }
      paint();recorder.requestData();recorder.stop();await stopped;
      const blob=new Blob(blobs,{type:recorder.mimeType.split(';')[0]||fmt.mime});
      if(blob.size<256)throw new Error('輸出檔案為空，可能是瀏覽器不支援此編碼');
      onProgress(1);
      const ext=blob.type.includes('mp4')?'mp4':'webm';
      return {blob,ext,width:outW,height:outH,hasAudio:!!context,filename:item.file.name.replace(/\.[^.]+$/,'')+'_cropped.'+ext,engine:'compat'};
    }finally{
      if(cleanupVideoFrame)cleanupVideoFrame();if(ticker)clearInterval(ticker);
      if(recorder?.state==='recording')try{recorder.stop();}catch{}
      if(stream)for(const tr of stream.getTracks())try{tr.stop();}catch{}
      if(context)try{await context.close();}catch{}
      if(wakeLock)try{await wakeLock.release();}catch{}
      dispose();
    }
  }
  function addOutput(res){const url=URL.createObjectURL(res.blob);s.outputs.push({...res,url});renderOutputs();}
  function clearOutputBuffers(){s.outputs.forEach(x=>URL.revokeObjectURL(x.url));s.outputs=[];renderOutputs();}
  $('clearOutputs').addEventListener('click',clearOutputBuffers);
  function renderOutputs(){
    $('outputs').hidden=!s.outputs.length;
    $('outputList').innerHTML=s.outputs.map((o,i)=>`<div class="output-item"><span title="${esc(o.filename)}">${esc(o.filename)} · ${fmtSize(o.blob.size)} · ${o.width}×${o.height}${o.engine==='fast'?` · ⚡ ${o.speed?.toFixed(1)||'?'} 倍速`:' · 相容模式'}</span><a href="${o.url}" download="${esc(o.filename)}">下載影片</a></div>`).join('');
  }
  $('exportSelected').addEventListener('click',()=>task('export',async()=>{
    const targets=s.items.filter(x=>x.checked&&x.crop&&x.crop.left+x.crop.right>=.5);
    if(!targets.length)return signal('請先分析或調整裁切範圍，並勾選要輸出的影片',4600);
    let done=0;
    for(const x of targets){checkCancel();paintProgress('正在匯出裁切影片',done/targets.length,`${x.file.name} · 準備轉碼`);
      try{
        const res=await exportOneAuto(x,p=>paintProgress('正在匯出裁切影片',(done+p)/targets.length,`${x.file.name} · ${Math.round(p*100)}%`));
        if(res){addOutput(res);signal(`${x.file.name} 已完成，請按「下載影片」保存`,4700);}
      }catch(e){if(s.cancel)throw e;console.error(e);signal(`${x.file.name}：${e.message}`,5000);}
      done++;
    }
    if(s.outputs.length)$('outputs').scrollIntoView({block:'nearest',behavior:'smooth'});
  }));

  // 同一版本號的 Service Worker 只快取程式資源，不快取影片。
  if('serviceWorker' in navigator&&location.protocol==='https:')window.addEventListener('load',()=>{
    navigator.serviceWorker.register('./sw.js').catch(e=>console.info('離線快取未啟用',e));
  });
  // 公開少量純邏輯 API 供回歸測試；不暴露使用者影片。
  window.VideoKeeperTest={detectMargins,buildCrop,bitDist,frameDist,similarity,getSignature,recordType,dimensionsFor,fastSupported};
})();
