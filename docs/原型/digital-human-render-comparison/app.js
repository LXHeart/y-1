/* No inference or external requests. Artwork colors are media colors, not UI tokens. */
(() => {
  const $ = id => document.getElementById(id);
  const audio = $('speech'), video = $('baseVideo');
  const ca = $('flat'), cb = $('portrait'), a = ca.getContext('2d'), b = cb.getContext('2d', {willReadFrequently:true});
  const source = document.createElement('canvas'); source.width=640; source.height=480;
  const s = source.getContext('2d',{willReadFrequently:true});
  const frames = [], costs = [];
  let state='loading', t=0, lastTick=0, raf=0, preview=false, audioContext, audioSource, audioDest, recorder, chunks=[], recording=false, exportURL;
  const reduced=matchMedia('(prefers-reduced-motion: reduce)');
  $('motion').checked=!reduced.matches;
  const setStatus = text => { $('status').textContent=text; };
  const names={sil:'闭嘴',PP:'闭唇',A:'张口',E:'横向口型',I:'窄口型',O:'圆唇',U:'收唇'};
  const shape = {sil:[0,0],PP:[.75,0],A:[1,1],E:[1.2,.43],I:[1.12,.26],O:[.65,.85],U:[.50,.40]};
  const sentence=TIMING.text.split('');
  $('transcript').replaceChildren(...sentence.map((c,i)=>{const e=document.createElement('span');e.textContent=c;e.dataset.char=i;return e;}));
  function energyAt(time){return TIMING.energy[Math.max(0,Math.min(TIMING.energy.length-1,Math.floor(time/.01)))]||0;}
  function mouthAt(time,force=false){
    if (!force && !preview && state!=='playing' && state!=='paused') return {w:0,h:0,id:'sil',idx:-1};
    const e=energyAt(time); if(e<.07)return {w:0,h:0,id:'sil',idx:-1};
    let cue=TIMING.cues.find(c=>time>=c.start&&time<c.end);
    if($('driver').value==='energy')return {w:.9,h:Math.min(1,e*1.6),id:'A',idx:cue?.idx??-1};
    if(!cue)return {w:0,h:0,id:'sil',idx:-1};
    const p=(time-cue.start)/(cue.end-cue.start), vowel=cue.vowel;
    let id=cue.closed&&p<.24?'PP':vowel;
    let [w,h]=shape[id];
    // Taper each estimated syllable without claiming phoneme alignment.
    const taper=Math.min(1,p/.13,(1-p)/.15);
    h*=Math.max(.25,Math.min(1,e*1.8))*Math.max(.2,taper);
    return {w,h,id,idx:cue.idx};
  }
  function path(ctx,d,fill,stroke,lw=3){const p=new Path2D(d);if(fill){ctx.fillStyle=fill;ctx.fill(p);}if(stroke){ctx.strokeStyle=stroke;ctx.lineWidth=lw;ctx.lineCap='round';ctx.stroke(p);}}
  function ellipse(ctx,x,y,rx,ry,fill){ctx.beginPath();ctx.ellipse(x,y,Math.max(.1,rx),Math.max(.1,ry),0,0,Math.PI*2);ctx.fillStyle=fill;ctx.fill();}
  const nailong = new Image(); nailong.src='assets/nailong.png';
  const cleanSprite=document.createElement('canvas');cleanSprite.width=500;cleanSprite.height=500;
  const nc=cleanSprite.getContext('2d');
  function prepareNailong(){
    nc.drawImage(nailong,0,0);
    // Replace the original small O-mouth with adjacent skin texture; no model.
    const patch=nc.getImageData(278,145,40,40),skin=nc.getImageData(246,145,40,40);
    for(let y=0;y<40;y++)for(let x=0;x<40;x++){
      const dist=Math.hypot((x-19)/14,(y-21)/17),alpha=Math.max(0,Math.min(1,(1.25-dist)*5));
      const i=(y*40+x)*4;for(let k=0;k<3;k++)patch.data[i+k]=patch.data[i+k]*(1-alpha)+skin.data[i+k]*alpha;
    }
    nc.putImageData(patch,278,145);
  }
  function drawFlat(time,m){
    a.clearRect(0,0,640,480);a.fillStyle='#eef2f8';a.fillRect(0,0,640,480);
    const motion=$('motion').checked?1:0;
    const breath=Math.sin(time*2.3)*1.5*motion, sway=Math.sin(time*1.6)*.008*motion;
    a.save();a.translate(320,436);a.rotate(sway);a.scale(.86,.86+breath*.001);a.translate(-250,-470);
    a.drawImage(cleanSprite,0,0);
    const blink=motion&&((time>0.2&&time%3.65<.12)||(time>4.55&&time<4.65));
    if(blink){
      ellipse(a,237,118,26,26,'#ffdc5b');ellipse(a,338,110,23,25,'#ffda55');
      path(a,'M221 120 Q238 132 253 119 M324 113 Q338 123 351 110',null,'#624718',3);
    }
    const w=m.id==='sil'?7:Math.max(5,m.w*12),h=m.h*13;
    if(h<1.2){path(a,`M${298-w} 165 Q298 170 ${298+w} 165`,null,'#68400c',2.4);}else{
      ellipse(a,298,166,w+1,h+1,'#b66b19');ellipse(a,298,166,w,h,'#321608');
      a.save();a.beginPath();a.ellipse(298,166,w,h,0,0,Math.PI*2);a.clip();
      ellipse(a,298,173+h*.45,w*.8,Math.max(3,h*.35),'#e67d72');a.restore();
    }
    a.restore();a.fillStyle='#5f6f84';a.font='13px Inter,system-ui';a.fillText('奶龙 · 全身 2D 动画小样',24,30);
  }
  function drawPortrait(time,m){
    if(video.readyState<2)return;
    s.drawImage(video,160,0,960,720,0,0,640,480);
    b.drawImage(source,0,0);
    if($('overlay').checked&&m.h>.02){
      // Manually positioned for this clip. No face detection or pretrained model.
      const cx=331+Math.sin(time*.8)*1.2,cy=354+Math.sin(time*.65)*1.5;
      const x0=235,y0=300,w=190,h=120;
      const raw=s.getImageData(x0,y0,w,h),out=b.createImageData(w,h);
      const hw=49*Math.max(.60,Math.min(1.1,m.w)),gap=13*m.h;
      for(let y=0;y<h;y++)for(let x=0;x<w;x++){
        const dx=x+x0-cx,dy=y+y0-cy,i=(y*w+x)*4;
        const edge=Math.max(0,1-(dx/hw)**2),open=gap*Math.sqrt(edge);
        if(edge>0&&Math.abs(dy)<open){
          const tooth=dy<(-open*.45)&&m.id!=='U'&&open>5;
          out.data[i]=tooth?186:41;out.data[i+1]=tooth?177:27;out.data[i+2]=tooth?152:29;out.data[i+3]=255;
        }else{
          const fade=Math.max(0,1-Math.abs(dy)/51);
          const sy=Math.max(0,Math.min(h-1,y-Math.sign(dy)*open*fade));
          const iy=Math.floor(sy),ny=Math.min(h-1,iy+1),f=sy-iy;
          for(let c=0;c<4;c++)out.data[i+c]=raw.data[(iy*w+x)*4+c]*(1-f)+raw.data[(ny*w+x)*4+c]*f;
        }
      }
      b.putImageData(out,x0,y0);
    }
    b.fillStyle='rgba(13,37,61,.78)';b.fillRect(0,440,640,40);b.fillStyle='#fff';b.font='14px Inter,system-ui';b.fillText('实验合成 · 非本人原声 · CC BY-SA 3.0',20,466);
  }
  const mix=document.createElement('canvas');mix.width=1280;mix.height=600;const mx=mix.getContext('2d');
  function paint(){
    const start=performance.now(),m=mouthAt(t);drawFlat(t,m);drawPortrait(t,m);
    $('time').textContent=`${t.toFixed(2)} / ${TIMING.duration.toFixed(2)} 秒`;$('seek').value=t;
    $('sync').textContent=`媒体时钟：声音 ${audio.currentTime.toFixed(2)} s / 真人底片 ${video.currentTime.toFixed(2)} s`;
    $('mouthLabel').textContent=`${names[m.id]} · ${$('driver').value==='text'?'规则估算':'音量基线'}`;
    for(const e of $('transcript').children)e.classList.toggle('active',Number(e.dataset.char)===m.idx);
    ca.dataset.mouth=m.id;cb.dataset.mouth=$('overlay').checked?m.id:'original';
    mx.fillStyle='#f6f9fc';mx.fillRect(0,0,1280,600);mx.fillStyle='#0d253d';mx.font='600 23px system-ui';mx.fillText('A · 奶龙全身 2D 嘴型',24,38);mx.fillText('B · 真人局部嘴型',664,38);mx.drawImage(ca,0,58);mx.drawImage(cb,640,58);mx.font='20px system-ui';mx.fillText(TIMING.text,24,575);mx.font='16px system-ui';mx.fillText('同一音频 / 同一估算嘴型 / 无推理',916,575);
    if(state==='playing')costs.push(performance.now()-start);
  }
  function tick(now){
    if(state==='playing'){
      t=Math.min(TIMING.duration,audio.currentTime);if(lastTick)frames.push(now-lastTick);lastTick=now;
      // Correct only a material drift; never run a second audible track.
      if(Math.abs(video.currentTime-t)>.12&&!video.seeking)video.currentTime=t;
    }
    paint();
    if(state==='playing')raf=requestAnimationFrame(tick);
  }
  function stopLoop(){cancelAnimationFrame(raf);raf=0;lastTick=0;}
  function report(){if(!frames.length)return;const sorted=[...costs].sort((x,y)=>x-y);const median=[...frames].sort((x,y)=>x-y)[Math.floor(frames.length/2)];$('metrics').textContent=`最近采样 ${frames.length} 帧；帧间隔中位数 ${median.toFixed(1)} ms；两画面绘制 P95 ${(sorted[Math.floor(sorted.length*.95)]||0).toFixed(1)} ms。仅当前浏览器，不代表手机性能。`;}
  function syncButtons(){const ready=state!=='loading'&&state!=='error';$('play').disabled=!ready||recording;$('play').textContent=state==='playing'?'暂停对比':'播放对比';$('interrupt').disabled=!ready||recording;$('replay').disabled=!ready||recording;$('seek').disabled=!ready||recording;$('record').disabled=!ready||recording;}
  async function play(reset=false){
    if(reset||audio.ended||t>=TIMING.duration){audio.currentTime=0;t=0;frames.length=0;costs.length=0;}
    preview=false;video.currentTime=t;
    try{if(audioContext?.state==='suspended')await audioContext.resume();await Promise.all([audio.play(),video.play()]);state='playing';setStatus(recording?'正在录制一轮':'播放中');syncButtons();stopLoop();raf=requestAnimationFrame(tick);}catch(e){state='error';audio.pause();video.pause();setStatus('播放失败，请重新加载或换用 Chrome 浏览器');syncButtons();console.error(e);}
  }
  function pause(interrupted=false){audio.pause();video.pause();t=audio.currentTime;state=interrupted?'interrupted':'paused';preview=!interrupted;stopLoop();setStatus(interrupted?'已打断 · 嘴部已关闭':'已暂停 · 画面冻结');report();syncButtons();paint();}
  $('play').onclick=()=>state==='playing'?pause():play();$('interrupt').onclick=()=>pause(true);$('replay').onclick=()=>play(true);
  $('seek').oninput=()=>{audio.pause();video.pause();stopLoop();t=Number($('seek').value);audio.currentTime=t;video.currentTime=t;state='paused';preview=true;setStatus('逐帧预览 · 嘴型为估算');syncButtons();paint();};
  video.addEventListener('seeked',()=>{if(state!=='playing')paint();});
  for(const id of ['driver','overlay','motion'])$(id).onchange=paint;
  $('theme').onclick=()=>{const dark=document.documentElement.dataset.theme==='light';document.documentElement.dataset.theme=dark?'dark':'light';$('theme').textContent=dark?'切换亮色':'切换暗色';};
  audio.onended=()=>{video.pause();t=TIMING.duration;state='ended';preview=false;stopLoop();report();setStatus('本轮结束 · 嘴部已关闭');paint();if(recording){setTimeout(()=>recorder?.state==='recording'&&recorder.stop(),250);}else if($('loop').checked){void play(true);}syncButtons();};
  $('record').onclick=async()=>{
    if(!window.MediaRecorder||!mix.captureStream){$('recordStatus').textContent='此浏览器不支持 Canvas 录制，请使用 Chrome。';return;}
    pause(true);recording=true;syncButtons();
    try{
      audioContext??=new AudioContext();await audioContext.resume();
      if(!audioSource){audioSource=audioContext.createMediaElementSource(audio);audioDest=audioContext.createMediaStreamDestination();audioSource.connect(audioDest);audioSource.connect(audioContext.destination);}
      const stream=mix.captureStream(25);for(const track of audioDest.stream.getAudioTracks())stream.addTrack(track);
      const mime=['video/webm;codecs=vp8,opus','video/webm','video/mp4'].find(x=>MediaRecorder.isTypeSupported(x));if(!mime)throw Error('No recorder codec');
      recorder=new MediaRecorder(stream,{mimeType:mime,videoBitsPerSecond:3500000});chunks=[];
      recorder.ondataavailable=e=>{if(e.data.size)chunks.push(e.data);};
      recorder.onstop=()=>{recording=false;for(const track of stream.getVideoTracks())track.stop();const reader=new FileReader();reader.onload=()=>{$('download').href=reader.result;$('download').download=`grassland-avatar-comparison.${mime.includes('mp4')?'mp4':'webm'}`;$('download').hidden=false;$('recordStatus').textContent='本轮录制完成，可下载';syncButtons();};reader.onerror=()=>{$('recordStatus').textContent='导出失败，请重新录制';syncButtons();};reader.readAsDataURL(new Blob(chunks,{type:mime.split(";")[0]}));};
      recorder.onerror=()=>{$('recordStatus').textContent='录制失败，请重试';recording=false;syncButtons();};
      recorder.start();$('recordStatus').textContent='正在录制，请保持页面在前台';await play(true);
      if(state==='error'&&recorder.state==='recording')recorder.stop();
    }catch(e){recording=false;$('recordStatus').textContent='录制不可用：'+e.message;syncButtons();}
  };
  function ready(el,event){return new Promise((res,rej)=>{if(el.readyState>=2)return res();el.addEventListener(event,res,{once:true});el.addEventListener('error',rej,{once:true});});}
  Promise.all([ready(audio,'loadeddata'),ready(video,'loadeddata'),document.fonts.ready,nailong.decode().then(prepareNailong)]).then(()=>{state='ready';video.currentTime=.5;syncButtons();setStatus('素材就绪 · 点击播放');paint();}).catch(()=>{state='error';setStatus('素材加载失败，请确认 assets 目录完整，或打开单文件版本');syncButtons();});
  document.addEventListener('visibilitychange',()=>{if(document.hidden&&state==='playing'){pause(true);if(recording&&recorder?.state==='recording')recorder.stop();}});
  window.addEventListener('pagehide',()=>{stopLoop();audio.pause();video.pause();if(recorder?.state==='recording')recorder.stop();audioContext?.close();if(exportURL)URL.revokeObjectURL(exportURL);});
})();
