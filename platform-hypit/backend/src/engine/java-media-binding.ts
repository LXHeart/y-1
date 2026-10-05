/** Bind the preallocated native ID before its detached worker can request media. */
export async function bindJavaMediaBuild(commandId: string, engineBuildId: string): Promise<void> {
  const base = process.env.HYPIT_JAVA_MEDIA_URL;
  if (!base) return;
  const token = process.env.HYPIT_INTERNAL_TOKEN;
  if (!token || token.length < 32) throw new Error('Java media bridge credential missing');
  const response = await fetch(new URL('/internal/hypit/media-segments/bind', base), {
    method: 'POST', redirect: 'error', signal: AbortSignal.timeout(30_000),
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
    body: JSON.stringify({ commandId, engineBuildId }),
  });
  await response.body?.cancel();
  if (!response.ok) throw new Error(`Java media build binding failed (${response.status})`);
}
