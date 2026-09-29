import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { publishRelease } from '../publish-curseforge.mjs';

function fixture({ stage = 'alpha', corrupt = false, missingVersion = false, redirectOutsideCF = false } = {}) {
  const repository = 'GenichiMaruo/WorldgenAssist';
  const version = `0.1.0${stage ? '-' + stage + '.5' : ''}+mc26.3`;
  const tag = 'v' + version;
  const bytes = new Map(['fabric', 'forge', 'neoforge'].map(loader => [
    `worldgen-assist${loader === 'fabric' ? '' : '-' + loader}-${version}.jar`, Buffer.from(`verified-${loader}-jar`),
  ]));
  const sums = [...bytes].map(([name, value]) => `${createHash('sha256').update(value).digest('hex')}  ${name}`).join('\n');
  bytes.set('SHA256SUMS.txt', Buffer.from(sums + '\n'));
  const assets = [...bytes].map(([name, value]) => ({ name, size: value.length, state: 'uploaded', browser_download_url: `https://github.com/${repository}/releases/download/${encodeURIComponent(tag)}/${name}` }));
  assets.push({ name: `worldgen-assist-${version}-sources.jar`, state: 'uploaded' });
  const release = { tag_name: tag, published_at: '2026-09-30T00:00:00Z', draft: false, prerelease: Boolean(stage), body: 'Release notes', assets };
  const posted = [], calls = [];
  const json = value => new Response(JSON.stringify(value), { headers: { 'Content-Type': 'application/json' } });
  const fetchImpl = async (url, options = {}) => {
    calls.push({ url, options });
    if (options.method === 'POST') {
      posted.push({ file: options.body.get('file'), metadata: JSON.parse(options.body.get('metadata')) });
      return json({ id: 1000 + posted.length });
    }
    if (url.startsWith('https://api.github.com/')) return json(release);
    if (url.startsWith('https://github.com/')) {
      const name = decodeURIComponent(new URL(url).pathname.split('/').at(-1));
      const value = Buffer.from(bytes.get(name));
      if (corrupt && name.includes('-neoforge-')) value[0] ^= 1;
      return new Response(value);
    }
    if (redirectOutsideCF) return new Response(null, { status: 302, headers: { location: 'https://example.com/collect-token' } });
    if (url.endsWith('/version-types')) return json([{ id: 1, name: 'Minecraft 26.3' }, { id: 2, name: 'Bukkit' }]);
    return json([
      ...(!missingVersion ? [{ id: 101, name: '26.3', gameVersionTypeID: 1 }] : []),
      { id: 999, name: '26.3', gameVersionTypeID: 2 },
      { id: 102, name: 'Fabric' }, { id: 103, name: 'Forge' },
      { id: 104, name: 'NeoForge' }, { id: 105, name: 'Java 25' },
    ]);
  };
  return { options: { repository, tag, projectId: '1234567', token: 'private-cf-test-token', githubToken: 'private-github-test-token', mode: 'publish', fetchImpl }, calls, posted };
}

test('publishes exactly three verified installable JARs with correct loader and dependency metadata', async () => {
  const state = fixture();
  const report = await publishRelease(state.options);
  assert.equal(state.posted.length, 3);
  assert.deepEqual(state.posted.map(entry => entry.metadata.gameVersions), [[101, 105, 102], [101, 105, 103], [101, 105, 104]]);
  assert.deepEqual(state.posted[0].metadata.relations.projects, [{ slug: 'fabric-api', type: 'requiredDependency' }]);
  assert.equal(state.posted[1].metadata.relations, undefined);
  assert(state.posted.every(entry => entry.metadata.releaseType === 'alpha' && !entry.file.name.includes('sources')));
  assert(report.files.every(entry => entry.status === 'uploaded' && entry.curseforgeFileId > 0));
  assert(state.calls.filter(call => call.url.startsWith('https://github.com/')).every(call => !call.options.headers?.Authorization));
});

test('checksum or Minecraft metadata failure blocks all uploads, including a single-loader resume', async () => {
  for (const failure of [{ corrupt: true }, { missingVersion: true }]) {
    const state = fixture(failure);
    await assert.rejects(publishRelease({ ...state.options, loader: 'fabric' }), /Checksum mismatch|version label unavailable/);
    assert.equal(state.posted.length, 0);
  }
});

test('check mode never uploads, retains alpha/beta/release classification, and excludes secrets from its report', async () => {
  for (const stage of ['alpha', 'beta', '']) {
    const state = fixture({ stage });
    const report = await publishRelease({ ...state.options, mode: 'check' });
    assert.equal(state.posted.length, 0);
    assert.equal(report.releaseType, stage || 'release');
    assert.equal(report.files.length, 3);
    assert(!JSON.stringify(report).includes(state.options.token));
    assert(!JSON.stringify(report).includes(state.options.githubToken));
    assert(!JSON.stringify(report).includes(state.options.projectId));
  }
});

test('a CurseForge redirect cannot send credentials to another service', async () => {
  const state = fixture({ redirectOutsideCF: true });
  await assert.rejects(publishRelease(state.options), /unexpected endpoint/);
  assert(!state.calls.some(call => call.url.startsWith('https://example.com/')));
  assert.equal(state.posted.length, 0);
});
