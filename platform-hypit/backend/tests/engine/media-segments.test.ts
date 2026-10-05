import assert from 'node:assert/strict';
import test from 'node:test';
import { spawnSync } from 'node:child_process';
import { mkdtemp, writeFile, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { loadHypit } from '../../src/engine/hypit-bootstrap.ts';
import type { Composition } from '@hypit/composition';
import type { BlobRef } from '@hypit/protocol';
await loadHypit(join(import.meta.dirname, '../../../.generated/hypit'));
const { compileHyperframesDocument, planMediaSegments } = await import('../../../.generated/hypit/packages/hyperframes/src/index.ts');
const { MemoryResourceStore } = await import('../../../.generated/hypit/packages/driver-node/src/index.ts');
const { renderHyperframesVisual } = await import('../../../.generated/hypit/packages/provider-hyperframes-local/src/render.ts');
const { renderMediaSegments } = await import('../../../.generated/hypit/packages/provider-hyperframes-local/src/media-segments.ts');
const space = { id: 'segments', durationSec: 1, frameRate: { numerator: 12, denominator: 1 } };
function fixture(artifact: BlobRef): Composition {
  const style = [{ name: 'position', value: 'absolute' }, { name: 'left', value: '0px' }, { name: 'top', value: '0px' }, { name: 'width', value: '64px' }, { name: 'height', value: '64px' }];
  return { id: 'mixed', canvas: { width: 64, height: 64, clearColor: '#000000' }, tracks: [{
    kind: 'visual', visualIr: 'hypit.visual-ir@1', id: 'track', programSpaceId: space.id, presents: [
      { id: 'photo', stacking: { order: 0, tieBreak: 'photo' }, span: { startFrame: 0, endFrameExclusive: 6 }, elements: [
        { id: 'image', kind: 'image', artifact, order: 0, style },
      ] },
      { id: 'animation', stacking: { order: 1, tieBreak: 'animation' }, span: { startFrame: 6, endFrameExclusive: 12 }, elements: [
        { id: 'box', kind: 'box', order: 0, style: [...style, { name: 'background-color', value: '#0000ff' }],
          animation: { keyframes: [{ atFrame: 0, style: [{ name: 'opacity', value: 1 }] }, { atFrame: 5, style: [{ name: 'opacity', value: 0.5 }] }] } },
      ] },
    ],
  }] };
}
const blob: BlobRef = { kind: 'blob', resource: 'res_photo', size: 1, mediaType: 'image/png' };
test('absolute frame partition and overlapping subtitles/effects remain native', () => {
  const composition = fixture(blob);
  assert.deepEqual(planMediaSegments(composition, { startFrame: 2, endFrameExclusive: 10 }).map(s => [s.startFrame, s.endFrameExclusive, !!s.media]), [[2, 6, true], [6, 10, false]]);
  const track = composition.tracks[0]!;
  if (track.kind !== 'visual') throw new Error('fixture');
  const overlapping = { ...composition, tracks: [{ ...track, presents: [...track.presents, { ...track.presents[1]!, span: { startFrame: 3, endFrameExclusive: 5 } }] }] };
  assert.deepEqual(planMediaSegments(overlapping, { startFrame: 0, endFrameExclusive: 12 }).map(s => [s.startFrame, s.endFrameExclusive, !!s.media]), [[0,3,true],[3,5,false],[5,6,true],[6,12,false]]);
  const first = track.presents[0]!;
  for (const replacement of [
    { ...first.elements[0]!, style: [{ name: 'filter', value: 'blur(2px)' }] },
    { ...first.elements[0]!, animation: { keyframes: [] } },
    { id: 'code', kind: 'program' as const, order: 0, style: [], program: { format: 'custom', payload: {}, artifacts: [] } },
  ]) {
    const changed = { ...composition, tracks: [{ ...track, presents: [{ ...first, elements: [replacement] }, track.presents[1]!] }] };
    assert.ok(planMediaSegments(changed, { startFrame: 0, endFrameExclusive: 12 }).every(s => !s.media));
  }
});
test('tampered HTML and kill switch cannot force a media route', async () => {
  const document = compileHyperframesDocument(fixture(blob), space);
  const options = { resources: new MemoryResourceStore() };
  const unexpected = async (): Promise<never> => { throw new Error('native not expected'); };
  assert.equal(await renderMediaSegments({ document: { ...document, html: document.html.replace('#000000', '#ffffff') } }, options, unexpected), undefined);
  const original = process.env.HYPIT_MEDIA_SEGMENTS; process.env.HYPIT_MEDIA_SEGMENTS = '0';
  try { assert.equal(await renderMediaSegments({ document }, options, unexpected), undefined); }
  finally { if (original === undefined) delete process.env.HYPIT_MEDIA_SEGMENTS; else process.env.HYPIT_MEDIA_SEGMENTS = original; }
});
test('cancel before rendering enters neither renderer', async () => {
  const controller = new AbortController(); controller.abort(new Error('cancelled segment'));
  await assert.rejects(renderHyperframesVisual({ document: compileHyperframesDocument(fixture(blob), space) }, { resources: new MemoryResourceStore(), signal: controller.signal }), /cancelled segment/);
});
test('real mixed FFmpeg/browser output preserves decoded frames and cut position', { timeout: 180_000 }, async t => {
  const directory = await mkdtemp(join(tmpdir(), 'y1-segment-test-')); t.after(() => rm(directory, { recursive: true, force: true }));
  const input = join(directory, 'red.png');
  const generated = spawnSync('ffmpeg', ['-v', 'error', '-f', 'lavfi', '-i', 'color=red:s=64x64', '-frames:v', '1', '-threads', '1', input]);
  assert.equal(generated.status, 0, generated.stderr.toString());
  const resources = new MemoryResourceStore(); const artifact = await resources.put(await readFile(input), 'image/png');
  const document = compileHyperframesDocument(fixture(artifact), space); const diagnostics: string[] = [];
  const visual = await renderHyperframesVisual({ document, range: { startFrame: 2, endFrameExclusive: 10 } }, {
    resources, processTimeoutMs: 30_000, frameTimeoutMs: 3000, workers: 1, browserGpu: 'software', onDiagnostic: async d => { diagnostics.push(d.message); },
  });
  assert.equal(visual.frameCount, 8); assert.ok(diagnostics.includes('Segment [2,6): ffmpeg-raster')); assert.ok(diagnostics.includes('Segment [6,10): hyperframes'));
  const path = join(directory, 'mixed.mp4'); await writeFile(path, (await resources.get(visual.artifact.resource))!);
  const decoded = spawnSync('ffmpeg', ['-v', 'error', '-i', path, '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-threads', '1', 'pipe:1']);
  assert.equal(decoded.status, 0, decoded.stderr.toString()); assert.equal(decoded.stdout.length, 8 * 64 * 64 * 3);
  for (let frame = 0; frame < 8; frame++) {
    const offset = (frame * 64 * 64 + 32 * 64 + 32) * 3; const [r, , b] = decoded.stdout.subarray(offset, offset + 3);
    assert.ok(frame < 4 ? r! > 200 && b! < 30 : b! > 80 && r! < 30, `frame ${frame} maintains boundary`);
  }
  const audioPath = join(directory, 'tone.wav');
  const tone = spawnSync('ffmpeg', ['-v','error','-f','lavfi','-i','sine=frequency=440:sample_rate=48000','-t',String(8/12),'-ac','2','-c:a','pcm_s16le',audioPath]);
  assert.equal(tone.status, 0, tone.stderr.toString());
  const audioArtifact = await resources.put(await readFile(audioPath), 'audio/wav');
  const { executeMuxProgramMedia } = await import('../../../.generated/hypit/packages/media-execution/src/index.ts');
  const mux = await executeMuxProgramMedia({ ffmpegPath: 'ffmpeg', ffprobePath: 'ffprobe', processTimeoutMs: 30_000, maxProbeOutputBytes: 4*1024*1024,
    artifacts: { get: source => resources.get(source.resource), open: async source => {
      const data = await resources.get(source.resource); return data ? (async function* () { yield data; })() : undefined;
    }, put: (data,type) => resources.put(data,type), putFile: async (file,type) => resources.put(await readFile(file),type) },
  }, { visual, audio: { artifact: audioArtifact, sampleFrames: 32000 } });
  assert.equal(mux.value.kind, 'inline');
  if (mux.value.kind !== 'inline') throw new Error('mux result');
  const final = mux.value.value as { frameCount: number; presentationSampleFrames: number; artifact: BlobRef };
  assert.equal(final.frameCount, 8); assert.equal(final.presentationSampleFrames, 32000);
  await writeFile(join(directory, 'with-audio.mp4'), (await resources.get(final.artifact.resource))!);
  const heard = spawnSync('ffmpeg', ['-v','error','-i',join(directory, 'with-audio.mp4'),'-map','0:a:0','-f','s16le','pipe:1']);
  assert.equal(heard.status, 0); assert.ok(heard.stdout.some(byte => byte !== 0), 'mux retains audible tone');

});

test('plain video trim uses absolute source frames and mixes with native animation', { timeout: 180_000 }, async t => {
  const directory = await mkdtemp(join(tmpdir(), 'y1-video-segment-')); t.after(() => rm(directory, { recursive: true, force: true }));
  const input = join(directory, 'source.mp4');
  const generated = spawnSync('ffmpeg', ['-v','error','-f','lavfi','-i','color=red:s=64x64:r=12:d=0.5','-f','lavfi','-i','color=lime:s=64x64:r=12:d=0.5','-filter_complex','[0:v][1:v]concat=n=2:v=1:a=0[v]','-map','[v]','-c:v','libx264','-threads','1','-pix_fmt','yuv420p',input]);
  assert.equal(generated.status, 0, generated.stderr.toString());
  const resources = new MemoryResourceStore(); const artifact = await resources.put(await readFile(input), 'video/mp4');
  const original = fixture(artifact); const track = original.tracks[0]!;
  if (track.kind !== 'visual') throw new Error('fixture');
  const first = track.presents[0]!;
  const composition: Composition = { ...original, tracks: [{ ...track, presents: [{ ...first, elements: [{
    ...first.elements[0]!, kind: 'video', artifact, sampling: { sourceFrameRate: space.frameRate, sourceFrameCount: 12, segments: [{
      target: { startFrame: 0, endFrameExclusive: 6 }, sourceFrame: { numerator: 2, denominator: 1 }, rate: { numerator: 1, denominator: 1 },
    }] },
  }] }, track.presents[1]!] }] };
  assert.equal(planMediaSegments(composition, { startFrame: 2, endFrameExclusive: 10 }, space.frameRate)[0]!.sourceStartFrame, 4);
  const diagnostics: string[] = [];
  const visual = await renderHyperframesVisual({ document: compileHyperframesDocument(composition, space) }, {
    resources, processTimeoutMs: 30_000, frameTimeoutMs: 3000, workers: 1, browserGpu: 'software', onDiagnostic: async d => { diagnostics.push(d.message); },
  });
  assert.equal(visual.frameCount, 12); assert.ok(diagnostics.includes('Segment [0,6): ffmpeg-video')); assert.ok(diagnostics.includes('Segment [6,12): hyperframes'));
  const output = join(directory,'trimmed.mp4'); await writeFile(output,(await resources.get(visual.artifact.resource))!);
  const decoded = spawnSync('ffmpeg',['-v','error','-i',output,'-f','rawvideo','-pix_fmt','rgb24','-threads','1','pipe:1']);
  assert.equal(decoded.status,0); assert.equal(decoded.stdout.length,12*64*64*3);
  for (let frame=0; frame<12; frame++) {
    const offset=(frame*64*64+32*64+32)*3;
    const [r,g,b]=decoded.stdout.subarray(offset,offset+3);
    assert.ok(frame<4 ? r!>200 && g!<30 : frame<6 ? g!>200 && r!<30 : b!>80 && r!<30, `source trim and animation frame ${frame}`);
  }
});

test('fractional frame-rate raster needs no browser; alpha stays native', { timeout: 90_000 }, async t => {
  const directory = await mkdtemp(join(tmpdir(), 'y1-raster-segment-')); t.after(() => rm(directory, { recursive: true, force: true }));
  for (const alpha of [false, true]) {
    const input = join(directory, `${alpha}.png`);
    const made = spawnSync('ffmpeg', ['-v','error','-f','lavfi','-i',alpha ? 'color=red@0.5:s=64x64,format=rgba' : 'color=red:s=64x64','-frames:v','1','-threads','1',input]);
    assert.equal(made.status, 0, made.stderr.toString());
    const resources = new MemoryResourceStore(); const artifact = await resources.put(await readFile(input),'image/png');
    const clock = { ...space, durationSec: 12*1001/30000, frameRate: { numerator: 30000, denominator: 1001 } };
    const diagnostics: string[] = [];
    const visual = await renderHyperframesVisual({ document: compileHyperframesDocument(fixture(artifact),clock), range: { startFrame: 0, endFrameExclusive: 3 } }, {
      resources, workers: 1, browserGpu: 'software', processTimeoutMs: 30_000, frameTimeoutMs: 3000,
      ...(alpha ? {} : { chromePath: '/nonexistent/browser' }), onDiagnostic: async d => { diagnostics.push(d.message); },
    });
    assert.equal(visual.frameCount,3); assert.deepEqual(visual.frameRate,clock.frameRate);
    assert.ok(diagnostics.includes(`Segment [0,3): ${alpha ? 'hyperframes' : 'ffmpeg-raster'}`));
  }
});
