import { createHash } from 'node:crypto';
import { mkdir, writeFile, appendFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const loaders = ['fabric', 'forge', 'neoforge'];
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');

export function releasePlan(release, tag) {
  const match = /^v(?<version>\d+\.\d+\.\d+(?:-(?<stage>alpha|beta|rc)\.\d+)?\+mc(?<minecraft>\d+\.\d+(?:\.\d+)?))$/.exec(tag);
  if (!match || release.tag_name !== tag || release.draft || !release.published_at) {
    throw new Error('A published, versioned Worldgen Assist release is required');
  }
  const { version, stage, minecraft } = match.groups;
  const type = stage === 'alpha' ? 'alpha' : stage ? 'beta' : 'release';
  if (Boolean(release.prerelease) !== (type !== 'release')) throw new Error('Tag and GitHub prerelease status disagree');
  const find = name => {
    const matches = release.assets.filter(asset => asset.name === name && asset.state === 'uploaded');
    if (matches.length !== 1) throw new Error(`Missing or ambiguous release asset: ${name}`);
    return matches[0];
  };
  return {
    version, minecraft, type, changelog: release.body || '',
    checksums: find('SHA256SUMS.txt'),
    files: loaders.map(loader => ({ loader, asset: find(`worldgen-assist${loader === 'fabric' ? '' : '-' + loader}-${version}.jar`) })),
  };
}

export async function publishRelease({ repository, tag, projectId, token, githubToken = '', mode = 'check', loader = 'all', fetchImpl = fetch, onReport = async () => {} }) {
  if (!/^[\w.-]+\/[\w.-]+$/.test(repository || '')) throw new Error('Invalid GitHub repository');
  if (!/^[1-9]\d*$/.test(projectId || '') || !token) throw new Error('CURSEFORGE_PROJECT_ID and CURSEFORGE_TOKEN are required');
  if (!['check', 'publish'].includes(mode) || !['all', ...loaders].includes(loader)) throw new Error('Invalid publish mode or loader');
  const request = async (url, options, label, allowedHost) => {
    for (let redirects = 0; redirects < 4; redirects++) {
      const target = new URL(url);
      if (target.protocol !== 'https:' || !allowedHost(target.hostname)) throw new Error(`${label}: unexpected endpoint`);
      const response = await fetchImpl(url, { ...options, redirect: 'manual', signal: AbortSignal.timeout(45_000) });
      if (response.status >= 300 && response.status < 400 && options.method !== 'POST') {
        const location = response.headers.get('location');
        if (!location) throw new Error(`${label}: missing redirect destination`);
        url = new URL(location, url).href;
        continue;
      }
      if (!response.ok) {
        // Keep the API's rejection reason, but never echo credentials or an
        // unbounded response into Actions logs. Do not retry a failed POST.
        let detail = await response.text().catch(() => '');
        for (const secret of [token, githubToken, projectId]) {
          if (secret) detail = detail.replaceAll(secret, '***');
        }
        detail = detail.replace(/[\u0000-\u001f\u007f]/g, ' ').trim().slice(0, 1600);
        throw new Error(`${label}: HTTP ${response.status}${detail ? `; ${detail}` : ''}`);
      }
      return { response, url };
    }
    throw new Error(`${label}: too many redirects`);
  };
  const githubHeaders = { Accept: 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28' };
  if (githubToken) githubHeaders.Authorization = `Bearer ${githubToken}`;
  const { response } = await request(`https://api.github.com/repos/${repository}/releases/tags/${encodeURIComponent(tag)}`, { headers: githubHeaders }, 'GitHub release', host => host === 'api.github.com');
  const plan = releasePlan(await response.json(), tag);
  const download = async asset => {
    const initial = new URL(asset.browser_download_url);
    if (initial.hostname !== 'github.com' || !initial.pathname.startsWith(`/${repository}/releases/download/`)) throw new Error('Unexpected release download URL');
    if (!Number.isSafeInteger(asset.size) || asset.size <= 0 || asset.size > 16 * 1024 * 1024) throw new Error('Release asset size is outside the upload bound');
    const { response } = await request(initial.href, {}, 'GitHub asset', host => host === 'github.com' || host.endsWith('.githubusercontent.com'));
    const bytes = Buffer.from(await response.arrayBuffer());
    if (bytes.length !== asset.size) throw new Error(`Downloaded size differs: ${asset.name}`);
    return bytes;
  };
  const sums = new Map();
  for (const line of (await download(plan.checksums)).toString('utf8').split(/\r?\n/)) {
    if (!line.trim()) continue;
    const match = /^([a-fA-F0-9]{64}) [ *]([^/\\]+)$/.exec(line);
    if (!match || sums.has(match[2])) throw new Error('Invalid or duplicate checksum manifest entry');
    sums.set(match[2], match[1].toLowerCase());
  }
  // Validate every loader before the first POST, even when resuming just one.
  for (const file of plan.files) {
    file.bytes = await download(file.asset);
    file.hash = sha256(file.bytes);
    if (file.hash !== sums.get(file.asset.name)) throw new Error(`Checksum mismatch: ${file.asset.name}`);
    if (file.asset.digest && file.asset.digest !== `sha256:${file.hash}`) throw new Error('GitHub asset digest disagrees with checksums');
  }
  let cfOrigin = 'https://minecraft.curseforge.com';
  const cfHeaders = { 'X-Api-Token': token };
  const cfHost = host => host.endsWith('.curseforge.com');
  const getCF = async path => {
    const result = await request(cfOrigin + path, { headers: cfHeaders }, 'CurseForge catalog', cfHost);
    cfOrigin = new URL(result.url).origin;
    const data = await result.response.json();
    if (!Array.isArray(data)) throw new Error('Unexpected CurseForge catalog response');
    return data;
  };
  const versions = await getCF('/api/game/versions');
  const types = await getCF('/api/game/version-types');
  const versionId = (name, minecraftOnly = false) => {
    const minecraftFamily = name.split('.').slice(0, 2).join('.').toLowerCase();
    const matches = versions.filter(version => version.name.toLowerCase() === name.toLowerCase()
      && (!minecraftOnly || types.some(type => type.id === version.gameVersionTypeID
        && (type.name.toLowerCase() === minecraftFamily || /^Minecraft(?:\s|$)/i.test(type.name)) && !/bukkit/i.test(type.name))));
    if (matches.length !== 1) {
      const candidates = versions.filter(version => version.name.toLowerCase() === name.toLowerCase());
      const catalog = candidates.map(version => ({ id: version.id, name: version.name,
        gameVersionTypeID: version.gameVersionTypeID, gameVersionTypeId: version.gameVersionTypeId,
        type: types.find(type => type.id === (version.gameVersionTypeID ?? version.gameVersionTypeId))?.name }));
      throw new Error(`CurseForge version label unavailable or ambiguous: ${name}; public catalog candidates=${JSON.stringify(catalog)}`);
    }
    return matches[0].id;
  };
  const minecraftId = versionId(plan.minecraft, true);
  const java = versions.filter(version => version.name === 'Java 25');
  const common = [minecraftId, ...(java.length === 1 ? [java[0].id] : [])];
  const report = { tag, mode, minecraft: plan.minecraft, releaseType: plan.type, files: [] };
  for (const file of plan.files) {
    file.metadata = {
      changelog: plan.changelog, changelogType: 'markdown',
      displayName: `Worldgen Assist ${plan.version} (${file.loader})`,
      gameVersions: [...common, versionId(file.loader === 'neoforge' ? 'NeoForge' : file.loader)],
      releaseType: plan.type, isMarkedForManualRelease: false,
      ...(file.loader === 'fabric' ? { relations: { projects: [{ slug: 'fabric-api', type: 'requiredDependency' }] } } : {}),
    };
    report.files.push({ loader: file.loader, name: file.asset.name, sha256: file.hash, gameVersions: file.metadata.gameVersions, status: 'verified' });
  }
  await onReport(report);
  if (mode === 'check') return report;
  for (const file of plan.files.filter(file => loader === 'all' || file.loader === loader)) {
    const form = new FormData();
    form.set('metadata', JSON.stringify(file.metadata));
    form.set('file', new Blob([file.bytes], { type: 'application/java-archive' }), file.asset.name);
    // Uploads are never automatically retried: a lost response may have accepted the file.
    const { response } = await request(`${cfOrigin}/api/projects/${projectId}/upload-file`, { method: 'POST', headers: cfHeaders, body: form }, `CurseForge ${file.loader} upload`, cfHost);
    const uploaded = await response.json();
    if (!Number.isSafeInteger(uploaded.id) || uploaded.id <= 0) throw new Error('CurseForge did not return a file ID');
    Object.assign(report.files.find(entry => entry.loader === file.loader), { status: 'uploaded', curseforgeFileId: uploaded.id });
    await onReport(report);
  }
  return report;
}

async function main() {
  const token = process.env.CURSEFORGE_TOKEN || '';
  const githubToken = process.env.GH_TOKEN || '';
  const mode = process.env.PUBLISH_MODE || 'check';
  if (mode === 'publish' && Number(process.env.GITHUB_RUN_ATTEMPT || '1') > 1) throw new Error('Use Run workflow with the remaining loader instead of rerunning an upload job');
  let latest;
  const directory = resolve('test-artifacts/curseforge-publish');
  await mkdir(directory, { recursive: true });
  try {
    const report = await publishRelease({
      repository: process.env.GITHUB_REPOSITORY, tag: process.env.RELEASE_TAG,
      projectId: (process.env.CURSEFORGE_PROJECT_ID || '').trim(), token, githubToken,
      mode, loader: process.env.PUBLISH_LOADER || 'all',
      onReport: async report => { latest = structuredClone(report); await writeFile(resolve(directory, 'report.json'), JSON.stringify(report, null, 2)); },
    });
    console.log(`CURSEFORGE_${mode.toUpperCase()}_COMPLETE tag=${report.tag} verified=${report.files.length} uploaded=${report.files.filter(file => file.status === 'uploaded').length}`);
  } finally {
    if (latest && process.env.GITHUB_STEP_SUMMARY) {
      const rows = latest.files.map(file => `| ${file.loader} | ${file.status} | ${file.curseforgeFileId || '—'} |`).join('\n');
      await appendFile(process.env.GITHUB_STEP_SUMMARY, `## CurseForge ${mode}\n\n${latest.tag} · Minecraft ${latest.minecraft} · ${latest.releaseType}\n\n| Loader | Status | CurseForge file ID |\n| --- | --- | --- |\n${rows}\n\nVerified published GitHub JARs against SHA256SUMS.txt. No rebuild.\n`);
    }
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch(error => {
    let message = error.message;
    for (const secret of [process.env.CURSEFORGE_TOKEN, process.env.GH_TOKEN, process.env.CURSEFORGE_PROJECT_ID]) {
      if (secret) message = message.replaceAll(secret, '***');
    }
    console.error(message);
    process.exitCode = 1;
  });
}
