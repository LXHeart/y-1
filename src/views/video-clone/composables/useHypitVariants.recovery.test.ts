// @vitest-environment happy-dom
import { afterEach, expect, test, vi } from 'vitest';
import { defineComponent } from 'vue';
import { mount } from '@vue/test-utils';
import { useHypitVariants } from './useHypitVariants';
import VariantsPanel from '../components/VariantsPanel.vue';

const row = { id: 'aaaaaaaa-0000-4000-8000-000000000001', batchJobId: 'bbbbbbbb-0000-4000-8000-000000000001', ordinal: 0, runFile: 'runs/variants/variant-0.svrun', state: 'failed' as const, attempt: 1, parameters: {} };
const ok = (data: unknown) => new Response(JSON.stringify({ success: true, data }), { headers: { 'content-type': 'application/json' } });
const quote = () => ok({ pricingId: 'price', planId: 'plan', rows: [{ need: 'media.generate', provider: 'test', currency: 'USD', unitAmount: '0.25', known: true }] });
function host() {
  let model!: ReturnType<typeof useHypitVariants>;
  const wrapper = mount(defineComponent({ setup() { model = useHypitVariants(); return () => null; } }));
  model.items.value = [{ ...row }];
  return { model, wrapper };
}
afterEach(() => vi.unstubAllGlobals());

test('switching projects during grant confirmation prevents the old build and preserves the new action state', async () => {
  let release!: (value: Response) => void;
  let grantEntered!: () => void;
  const entered = new Promise<void>(r => { grantEntered = r; });
  const deferred = new Promise<Response>(r => { release = r; });
  const builds: string[] = [];
  vi.stubGlobal('fetch', vi.fn(async input => {
    const url = String(input);
    if (url.endsWith('/plan')) return ok({ plan: { id: 'plan' }, providers: [] });
    if (url.endsWith('/pricing')) return quote();
    if (url.endsWith('/execution-grants')) { grantEntered(); return deferred; }
    if (url.endsWith('/build')) { builds.push(url); return ok({ buildId: 'old-build' }); }
    throw new Error('unexpected request');
  }));
  const { model, wrapper } = host();
  try {
    await model.act('project-A', model.items.value[0]!, 'build');
    const first = model.confirmGrant('project-A');
    await entered;
    await model.confirmGrant('project-A'); // 连点不产生第二个授权
    model.reset();
    model.items.value = [{ ...row }];
    await model.act('project-B', model.items.value[0]!, 'build');
    const currentQuote = model.pendingGrant.value;
    release(ok({ grantId: 'old-grant' }));
    await first;
    expect(builds).toEqual([]);
    expect(model.pendingGrant.value).toBe(currentQuote);
    expect(model.granting.value).toBe(false);
    expect(model.error.value).toBeNull();
    expect(vi.mocked(fetch).mock.calls.filter(([url]) => String(url).endsWith('/execution-grants'))).toHaveLength(1);
  } finally { model.reset(); wrapper.unmount(); }
});

test('retry followed by dismissing authorization must retain a way to resume build', async () => {
  vi.stubGlobal('fetch', vi.fn(async (input) => {
    const url = String(input);
    if (url.endsWith('/retry')) return ok({ id: row.id, state: 'queued', attempt: 2 });
    if (url.endsWith('/plan')) return ok({ plan: { id: 'plan' }, providers: [] });
    if (url.endsWith('/pricing')) return quote();
    throw new Error('unexpected request ' + url);
  }));
  const { model, wrapper } = host();
  let panel;
  try {
    expect(await model.act('project-A', model.items.value[0]!, 'retry')).toBe('awaiting-grant');
    model.dismissGrant();
    panel = mount(VariantsPanel, { props: { items: model.items.value, loading: false, error: null, creating: false, actingId: null } });
    expect(panel.find('[data-testid="clone-variant-build"]').exists(), 'queued attempt 2 with no build must be resumable after dismissGrant').toBe(true);
  } finally { panel?.unmount(); model.reset(); wrapper.unmount(); }
});

test('a late pricing response from project A must not repopulate authorization after reset to B', async () => {
  let release!: (value: Response) => void;
  let pricingEntered!: () => void;
  const entered = new Promise<void>(r => { pricingEntered = r; });
  const deferred = new Promise<Response>(r => { release = r; });
  vi.stubGlobal('fetch', vi.fn(async input => {
    if (String(input).endsWith('/plan')) return ok({ plan: { id: 'plan-A' }, providers: [] });
    if (String(input).endsWith('/pricing')) { pricingEntered(); return deferred; }
    throw new Error('unexpected request');
  }));
  const { model, wrapper } = host();
  try {
    const action = model.act('project-A', model.items.value[0]!, 'build');
    await entered;
    model.reset();
    release(quote());
    await action;
    expect(model.pendingGrant.value, 'reset must invalidate old project mutations as well as refresh').toBeNull();
  } finally { model.reset(); wrapper.unmount(); }
});
