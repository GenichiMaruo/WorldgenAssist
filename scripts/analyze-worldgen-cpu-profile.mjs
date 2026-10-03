import fs from 'node:fs';
import path from 'node:path';
const root=path.resolve(process.argv[2]??'');
const suffix=process.argv[3]??'';
if(!/^(|-[a-z0-9-]+)$/.test(suffix))throw Error('Invalid analysis suffix');
const evidence=path.resolve('test-artifacts')+path.sep;
if(!root.startsWith(evidence))throw Error('Profile root must be a workspace evidence child');
const summaryFile=fs.existsSync(path.join(root,'runtime-summary.json'))?'runtime-summary.json':'summary.json';
const summary=JSON.parse(fs.readFileSync(path.join(root,summaryFile),'utf8').replace(/^\uFEFF/,''));
if(summary.status!=='COMPLETE'||summary.results.length!==2)throw Error('Original profile batch incomplete');
const table=(map,total)=>[...map].sort((a,b)=>b[1]-a[1]).map(([name,samples])=>({name,samples,percent:100*samples/total}));
const outputs=[];
// Read one event at a time: deep JFR exports exceed V8's single-string limit.
async function* streamEvents(file){
  let prefix='',started=false,depth=0,quoted=false,escaped=false,parts=[];
  for await(let chunk of fs.createReadStream(file,{encoding:'utf8',highWaterMark:262144})){
    if(!started){prefix+=chunk;const marker=/"events"\s*:\s*\[/.exec(prefix);if(!marker){prefix=prefix.slice(-64);continue}chunk=prefix.slice(marker.index+marker[0].length);prefix='';started=true}
    let begin=depth?0:-1;
    for(let index=0;index<chunk.length;index++){
      const character=chunk[index];
      if(!depth){if(character===']')return;if(character!=='{')continue;depth=1;begin=index;parts=[];continue}
      if(quoted){if(escaped)escaped=false;else if(character==='\\')escaped=true;else if(character==='"')quoted=false;continue}
      if(character==='"'){quoted=true;continue}
      if(character==='{')depth++;
      else if(character==='}'&&--depth===0){parts.push(chunk.slice(begin,index+1));yield JSON.parse(parts.join(''));parts=[];begin=-1}
    }
    if(depth)parts.push(chunk.slice(begin));
  }
  if(!started||depth)throw Error('Incomplete JFR JSON export');
}
for(const mode of ['vanilla','assisted']){
  const directory=path.join(root,mode),windows=JSON.parse(fs.readFileSync(path.join(directory,'remote-evidence','flight-recording-windows.json'),'utf8').replace(/^\uFEFF/,''));
  const window=windows.find(x=>x.phase==='measured');
  const begin=Date.parse(window.start_utc),end=Date.parse(window.end_utc);
  const leaf=new Map(),inclusive=new Map(),threads=new Map(),categories=new Map(),callers=new Map(),noiseOrigins=new Map();let total=0,truncated=0;
  const add=(map,name)=>map.set(name,(map.get(name)??0)+1);
  const groups=[
    ['density samplers',n=>n.startsWith('net.minecraft.world.level.levelgen.densityfunction.')],
    ['material rule density',n=>n.startsWith('net.minecraft.world.level.levelgen.material.MaterialRuleContext.getDensitiesInChunk')],
    ['surface depth noise',n=>n.startsWith('net.minecraft.world.level.levelgen.material.MaterialSystem.getSurfaceDepth')||n.startsWith('net.minecraft.world.level.levelgen.material.MaterialSystem.getSurfaceSecondary')],
    ['terrain block fill',n=>n==='net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator.doFill'],
    ['surface material',n=>n==='net.minecraft.world.level.levelgen.material.MaterialSystem.buildSurface'],
    ['carvers',n=>n==='net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator.generateCarvers'],
    ['biome selection',n=>n.startsWith('net.minecraft.world.level.biome.')],
    ['features',n=>n==='net.minecraft.world.level.chunk.ChunkGenerator.applyBiomeDecoration'],
    ['aquifers',n=>n.startsWith('net.minecraft.world.level.levelgen.Aquifer$')],
    ['remote validation',n=>n.startsWith('io.github.genichimaruo.worldgenassist.server.RemoteDensityValidator.')],
    ['remote management',n=>n.startsWith('io.github.genichimaruo.worldgenassist.server.RemoteWorldgenManager.')],
    ['remote grid integration',n=>n.startsWith('io.github.genichimaruo.worldgenassist.server.RemoteGridSamplers')||n.startsWith('io.github.genichimaruo.worldgenassist.server.RemoteDensityField')],
    ['eligibility checks',n=>n.startsWith('io.github.genichimaruo.worldgenassist.server.RemoteWorldgenEligibility.')],
    ['logging',n=>n.startsWith('org.apache.logging.log4j.')],
    ['compression',n=>n.startsWith('java.util.zip.')],
    ['heightmaps',n=>n.startsWith('net.minecraft.world.level.levelgen.Heightmap.')]
  ];
  for await(const event of streamEvents(path.join(directory,'execution-samples-deep.json'))){
    if(event.type!=='jdk.ExecutionSample')continue;
    const values=event.values,when=Date.parse(values.startTime),frames=values.stackTrace?.frames;
    if(when<begin||when>end||!frames?.length)continue;
    total++;if(values.stackTrace.truncated)truncated++;
    const names=frames.map(f=>f.method.type.name.replaceAll('/','.')+'.'+f.method.name);
    if(names.includes('net.minecraft.world.level.levelgen.synth.GradientNoise.permute')) {
      const origin=names.some(n=>n.startsWith('io.github.genichimaruo.worldgenassist.server.RemoteDensityValidator.'))?'validation':
        names.some(n=>n.startsWith('net.minecraft.world.level.levelgen.material.MaterialRuleContext.getDensitiesInChunk'))?'material rule density':
        names.some(n=>n.startsWith('net.minecraft.world.level.chunk.ChunkGenerator.doCreateBiomes')||n.startsWith('net.minecraft.world.level.chunk.ChunkGenerator.lambda$createBiomes'))?'initial biome density':
        names.includes('net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator.doFill')?'terrain fill density':
        names.includes('net.minecraft.world.level.levelgen.material.MaterialSystem.buildSurface')?'other surface noise':
        'other noise';
      add(noiseOrigins,origin);
    }
    for(const target of ['net.minecraft.world.level.levelgen.densityfunction.DensityVolume.indexOfBlock','io.github.genichimaruo.worldgenassist.server.RemoteWorldgenEligibility.evaluate','net.minecraft.world.level.levelgen.synth.GradientNoise.permute']){
      const index=names.indexOf(target);if(index>=0)add(callers,names.slice(index,index+9).join(' <- '));
    }
    add(leaf,names[0]);for(const name of new Set(names))add(inclusive,name);
    add(threads,values.sampledThread?.javaName??'unknown');
    for(const [group,predicate] of groups)if(names.some(predicate))add(categories,group);
  }
  if(total<100)throw Error('Too few computational samples');
  const result={mode,artifact_sha256:summary.artifact_sha256,computational_samples:total,truncated_stacks:truncated,threads:table(threads,total),categories:table(categories,total),leaf_methods:table(leaf,total).slice(0,60),inclusive_methods:table(inclusive,total).slice(0,100),target_callers:table(callers,total),gradient_noise_origins:table(noiseOrigins,total),definition:'Measured-window jdk.ExecutionSample only; NativeMethodSample waits excluded. Inclusive categories overlap. Noise origins classify full stacks containing GradientNoise.permute, percentages use all computational samples. Sampling percentages are neither CPU duration nor end-to-end speedup.'};
  fs.writeFileSync(path.join(directory,'computational-hot-methods'+suffix+'.json'),JSON.stringify(result,null,2));outputs.push(result);
}
fs.writeFileSync(path.join(root,'computational-analysis'+suffix+'.json'),JSON.stringify({artifact_sha256:summary.artifact_sha256,outputs},null,2));
for(const result of outputs)console.log(JSON.stringify({mode:result.mode,samples:result.computational_samples,truncated:result.truncated_stacks,categories:result.categories,top_leaf:result.leaf_methods.slice(0,8),threads:result.threads.slice(0,7)}));
