import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtemp, writeFile, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { loadHypit } from '../../src/engine/hypit-bootstrap.ts';
import { bindJavaMediaBuild } from '../../src/engine/java-media-binding.ts';
await loadHypit(join(import.meta.dirname,'../../../.generated/hypit'));
const { renderJavaSegment } = await import('../../../.generated/hypit/packages/provider-hyperframes-local/src/java-segment-bridge.ts');
const { MemoryResourceStore } = await import('../../../.generated/hypit/packages/driver-node/src/index.ts');

test('Java binding precedes rendering; credential and spec stay on internal transport', async t => {
  const dir=await mkdtemp(join(tmpdir(),'media-bridge-')); t.after(()=>rm(dir,{recursive:true,force:true}));
  const oldUrl=process.env.HYPIT_JAVA_MEDIA_URL, oldToken=process.env.HYPIT_INTERNAL_TOKEN;
  t.after(()=>{ if(oldUrl===undefined)delete process.env.HYPIT_JAVA_MEDIA_URL;else process.env.HYPIT_JAVA_MEDIA_URL=oldUrl;
    if(oldToken===undefined)delete process.env.HYPIT_INTERNAL_TOKEN;else process.env.HYPIT_INTERNAL_TOKEN=oldToken; });
  const spec={kind:'image',width:320,height:240,fpsNumerator:30,fpsDenominator:1,frameCount:30,start:0,trimUnit:'frames',fit:'fill',crf:23};
  const received: string[]=[]; let status=200;
  const server=createServer(async(req,res)=>{
    received.push(req.url!); assert.equal(req.headers.authorization,`Bearer ${'t'.repeat(32)}`);
    const chunks=[];for await(const data of req)chunks.push(data);
    if(req.url?.endsWith('/bind')) assert.deepEqual(JSON.parse(Buffer.concat(chunks).toString()),{commandId:'command',engineBuildId:'build'});
    else {assert.deepEqual(JSON.parse(String(req.headers['x-segment-spec'])),spec);assert.deepEqual(Buffer.concat(chunks),Buffer.from('source'));}
    res.writeHead(status,{'x-segment-cache':'hit'});res.end('rendered');
  });
  await new Promise<void>(resolve=>server.listen(0,'127.0.0.1',resolve));
  t.after(()=>new Promise<void>(resolve=>{server.closeAllConnections();server.close(()=>resolve());}));
  const address=server.address();assert.ok(address && typeof address==='object');
  process.env.HYPIT_JAVA_MEDIA_URL=`http://127.0.0.1:${address.port}`;process.env.HYPIT_INTERNAL_TOKEN='t'.repeat(32);
  await bindJavaMediaBuild('command','build');
  const input=join(dir,'input'),output=join(dir,'output');await writeFile(input,'source');
  const options={resources:new MemoryResourceStore(),mediaBridgeBuildId:'build'};
  assert.equal(await renderJavaSegment(input,output,spec,options,new AbortController().signal),true);
  assert.equal(await readFile(output,'utf8'),'rendered');assert.deepEqual(received,['/internal/hypit/media-segments/bind','/internal/hypit/media-segments/build']);
  for (const override of [{width:4096},{fpsNumerator:120},{frameCount:18001},{start:1},{crf:41}]) {
    assert.equal(await renderJavaSegment(input,output,{...spec,...override},options,new AbortController().signal),false);
  }
  assert.equal(received.length,2,'unsupported Java specifications must not make HTTP requests');
  status=403;await assert.rejects(renderJavaSegment(input,output,spec,options,new AbortController().signal),/403/);
  await assert.rejects(renderJavaSegment(input,output,spec,{resources:new MemoryResourceStore()},new AbortController().signal),/trusted build identity/);
});
