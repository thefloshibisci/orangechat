const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const source = fs.readFileSync(path.join(__dirname, '../main.js'), 'utf8');
const png = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=';
function harness() {
  const store = new Map(), calls = [];
  const context = {
    exports: {}, config: {api_key:'test-only-not-a-credential'},
    dataStore: {get:k=>store.get(k), set:(k,v)=>{store.set(k,v);return true;}, setDurable:(k,v)=>{store.set(k,v);return true;}},
    imageAssets: {prepare:(id,purpose)=>{
      const meta=JSON.parse(store.get('nai.asset.'+id)||'null');
      if (!meta) throw Error('Reference asset unavailable');
      const bytes=Array.from({length:meta.chunks},(_,i)=>store.get('nai.asset.'+id+'.'+i)).join('');
      return {image_id:id,source_sha256:crypto.createHash('sha256').update(bytes).digest('hex'),preparation:'mock-v1'};
    }},
    fetch: async (url,opts)=>{
      calls.push({url,opts,body:JSON.parse(opts.body)});
      return context.response || (url.endsWith('encode-vibe')
        ? {ok:true,status:200,headers:{'content-type':'application/octet-stream'},base64:()=>Buffer.from('opaque-vibe-bytes').toString('base64')}
        : {ok:true,status:200,json:()=>({images:[{base64:png}]})});
    }
  };
  vm.createContext(context); vm.runInContext(source,context);
  function asset(id,bytes=png) {store.set('nai.asset.'+id,JSON.stringify({chunks:1,mime:'image/png'}));store.set('nai.asset.'+id+'.0',bytes);}
  asset('source_cat'); asset('source_person',png+'AAAA'); asset('base_image');
  return {context,store,calls,asset,tool:async(name,args)=>JSON.parse(JSON.stringify(await context.exports[name](args)))};
}
const vibe = (image_id='source_cat', information_extracted=.7, strength=.6)=>({image_id,information_extracted,strength});
const precise = ()=>({image_id:'source_person',type:'character',strength:.8,fidelity:.9});
const defaults = {transport:'native',model:'nai-diffusion-4-5-full',vibe_enabled:true,vibe_references:[vibe()]};
const draw = (overrides={},extra={})=>({prompt:'a small cat playing with a ball',request_id:'draw_test_001',overrides:JSON.stringify({...defaults,...overrides}),...extra});
const encode = (extra={})=>({image_id:'source_cat',model:'nai-diffusion-4-5-full',information_extracted:.7,request_id:'encode_test_001',confirmed:true,...extra});

test('offline preview shows final active refs and exact normalized strengths without payload',async()=>{
  const h=harness(),r=await h.tool('nai_draw',draw({vibe_references:[vibe(),vibe('source_person',.7,.8)]},{preview:true}));
  assert.equal(r.success,true); assert.equal(h.calls.length,0);
  assert.equal(r.references.vibe[0].encoding_status,'not_encoded');
  assert.deepEqual(r.request.parameters.reference_strength_multiple,[.6/1.4,.8/1.4]);
  assert.equal(JSON.stringify(r).includes(png),false);
});
test('reference import/preview does not choose img2img or mutate saved defaults',async()=>{
  const h=harness(); const before=JSON.stringify(h.context.NAI.initial());
  const r=await h.tool('nai_draw',draw({}, {preview:true}));
  assert.equal(r.effective.mode,'generate'); assert.equal(r.effective.image_id,'');
  assert.equal(h.store.has('nai.studio.v2'),false);assert.equal(JSON.stringify(h.context.NAI.initial()),before);
});
test('URL, V5 and incompatible references rejected before network',async()=>{
  for (const over of [{transport:'url'},{model:'nai-diffusion-5-full'},{precise_enabled:true,precise_references:[precise()]}]) {
    const h=harness(),r=await h.tool('nai_draw',draw(over));assert.equal(r.success,false);assert.equal(h.calls.length,0);
  }
});
test('missing encoding does not auto-charge or create a sending generation job',async()=>{
  const h=harness(),r=await h.tool('nai_draw',draw());assert.equal(r.success,false);assert.equal(h.calls.length,0);
  assert.equal(h.store.has('nai.job.draw_test_001'),false);
});
test('encoding preview and unconfirmed request cost nothing',async()=>{
  const h=harness();assert.equal((await h.tool('nai_vibe_encode',encode({preview:true}))).estimated_anlas,2);
  assert.equal((await h.tool('nai_vibe_encode',encode({confirmed:false}))).success,false);assert.equal(h.calls.length,0);
});
test('separate encoding, reference generation, private bytes only in outbound body',async()=>{
  const h=harness();assert.equal((await h.tool('nai_vibe_encode',encode())).success,true);
  const r=await h.tool('nai_draw',draw());assert.equal(r.success,true);assert.equal(h.calls.length,2);
  assert.equal(h.calls[0].opts.responseEncoding,'base64'); assert.equal(h.calls[0].opts.retry,false);
  assert.equal(h.calls[1].body.action,'generate');assert.equal(h.calls[1].body.parameters.image,undefined);
  assert.deepEqual(h.calls[1].body.parameters.reference_image_multiple,[Buffer.from('opaque-vibe-bytes').toString('base64')]);
  for (const [key,value] of h.store) if (/nai\.(job|encode\.job)\./.test(key)) {
    assert.equal(value.includes(png),false);assert.equal(value.includes('b3BhcXVl'),false);
  }
  assert.equal(JSON.stringify(await h.tool('nai_status',{})).includes('b3BhcXVl'),false);
});
test('cache reuses identical content even under a different material id and strength',async()=>{
  const h=harness();await h.tool('nai_vibe_encode',encode());h.asset('same_picture');
  const r=await h.tool('nai_vibe_encode',encode({image_id:'same_picture',request_id:'new_encode_id'}));
  assert.equal(r.reused,true);assert.equal(h.calls.length,1);
  const generated=await h.tool('nai_draw',draw({vibe_references:[vibe('same_picture',.7,.2)]}));
  assert.equal(generated.success,true);assert.deepEqual(h.calls[1].body.parameters.reference_strength_multiple,[.2]);
});
test('source mutation, model and information changes invalidate cached encoding',async()=>{
  for (const change of ['source','model','information']) {
    const h=harness();await h.tool('nai_vibe_encode',encode());let extra={};
    if(change==='source')h.asset('source_cat',png+'BBBB');
    if(change==='model')extra.model='nai-diffusion-4-5-curated';
    if(change==='information')extra.vibe_references=[vibe('source_cat',.8)];
    const r=await h.tool('nai_draw',draw(extra));assert.equal(r.success,false);assert.equal(h.calls.length,1);
  }
});
test('unknown encoding outcome never auto-reposts under same or new request id',async()=>{
  const h=harness();h.context.response={ok:true,status:200,headers:{'content-type':'application/json'},base64:()=>''};
  const first=await h.tool('nai_vibe_encode',encode());assert.equal(first.status,'response_unreadable');
  await h.tool('nai_vibe_encode',encode());await h.tool('nai_vibe_encode',encode({request_id:'another_encoding'}));
  const r=await h.tool('nai_vibe_result',{id:'encode_test_001'});assert.equal(r.status,'response_unreadable');assert.equal(h.calls.length,1);
});
test('generation response parse failure journal remains read-only on retry',async()=>{
  const h=harness();h.context.response={ok:true,status:200,json:()=>{throw Error('bad response');}};
  const params=draw({vibe_enabled:false});const r=await h.tool('nai_draw',params);assert.equal(r.status,'response_unreadable');
  await h.tool('nai_draw',params);await h.tool('nai_result',{id:params.request_id});assert.equal(h.calls.length,1);
});
test('explicit disable overrides saved references without overwriting defaults',async()=>{
  const h=harness();const s=JSON.parse(JSON.stringify(h.context.NAI.initial()));Object.assign(s.defaults,defaults);
  const saved=JSON.stringify(s);h.store.set('nai.studio.v2',saved);
  const r=await h.tool('nai_draw',draw({vibe_enabled:false}));assert.equal(r.success,true);
  assert.equal(h.calls[0].body.parameters.reference_image_multiple,undefined);assert.equal(h.store.get('nai.studio.v2'),saved);
});
test('precise parameters, reversed fidelity, no invented character binding',async()=>{
  const h=harness(),r=await h.tool('nai_draw',draw({vibe_enabled:false,precise_enabled:true,precise_references:[precise()]}));
  assert.equal(r.success,true);const p=h.calls[0].body.parameters;
  assert.deepEqual(p.director_reference_strength_values,[.8]);assert.deepEqual(p.director_reference_secondary_strength_values,[.1]);
  assert.deepEqual(p.director_reference_descriptions,[{caption:{base_caption:'character',char_captions:[]},legacy_uc:false}]);
  assert.equal(p.reference_image_multiple,undefined);assert.equal(p.image,undefined);
});
test('img2img base and reference source stay independent',async()=>{
  const h=harness(),r=await h.tool('nai_draw',draw({mode:'img2img',image_id:'base_image',vibe_enabled:false,precise_enabled:true,precise_references:[precise()]}));
  assert.equal(r.success,true);const p=h.calls[0].body.parameters;
  assert.equal(p.image,png);assert.equal(p.director_reference_images[0],png+'AAAA');assert.equal('seed' in p,false);
  assert.equal(h.store.get('nai.job.draw_test_001').includes(png),false);
});
test('reference limits, ranges and unsupported mapping rejected',async()=>{
  for(const refs of [Array.from({length:17},()=>vibe()),[vibe('source_cat',0)],[{...vibe(),character_index:0}]]) {
    const h=harness();assert.equal((await h.tool('nai_draw',draw({vibe_references:refs}))).success,false);assert.equal(h.calls.length,0);
  }
});
test('failed durable journal prevents paid requests',async()=>{
  const h=harness();h.context.dataStore.setDurable=()=>false;
  assert.equal((await h.tool('nai_vibe_encode',encode())).success,false);
  assert.equal((await h.tool('nai_draw',draw({vibe_enabled:false}))).success,false);
  assert.equal(h.calls.length,0);
});
test('old host preserves ordinary drawing but does not silently downgrade references',async()=>{
  const h=harness();delete h.context.dataStore.setDurable;
  assert.equal((await h.tool('nai_draw',draw({vibe_enabled:false}))).success,true);
  const r=await h.tool('nai_vibe_encode',encode());assert.equal(r.success,false);assert.equal(h.calls.length,1);
});
test('same encoding request id with changed parameters reports conflict',async()=>{
  const h=harness(); await h.tool('nai_vibe_encode',encode());
  const result=await h.tool('nai_vibe_encode',encode({information_extracted:.9}));
  assert.equal(result.success,false);assert.match(result.error,/request_id/);assert.equal(h.calls.length,1);
});
function saveHarness(reply) {
  const ui=fs.readFileSync(path.join(__dirname,'../ui/index.html'),'utf8');
  const events=[],calls=[];
  const context={parse:x=>typeof x==='string'?JSON.parse(x):x,say:x=>events.push(x),
    Bridge:{saveImageAsset:async key=>{calls.push(key);return reply;}},asset:async()=>({mime:'image/png',b64:png})};
  vm.createContext(context);
  vm.runInContext(ui.slice(ui.indexOf('var savingImage = false;'),ui.indexOf("$('loadChatImage').onclick")),context);
  return {context,events,calls};
}
test('UI save success uses asset key only',async()=>{
  const h=saveHarness({success:true});await h.context.downloadAsset('source_cat');
  assert.deepEqual(h.calls,['nai.asset.source_cat']);assert.deepEqual(h.events,['图片已保存']);
});
test('UI save cancel is not reported as success',async()=>{
  for(const reply of [{success:false,cancelled:true},{success:false,error:'User cancelled'}]) {
    const h=saveHarness(reply);await h.context.downloadAsset('source_cat');assert.deepEqual(h.events,['已取消保存']);
  }
});
test('UI save failure unlocks retry and never reports success',async()=>{
  const h=saveHarness({success:false,error:'disk full'});
  await assert.rejects(()=>h.context.downloadAsset('source_cat'),/disk full/);
  assert.equal(h.context.savingImage,false);assert.equal(h.events.length,0);
});
test('old host blocks large save URL fallback',async()=>{
  const h=saveHarness(null);delete h.context.Bridge.saveImageAsset;
  h.context.Bridge.saveFileAs=()=>{throw Error('must not transport');};
  h.context.asset=async()=>({mime:'image/png',b64:'A'.repeat(1000001)});
  await assert.rejects(()=>h.context.downloadAsset('source_cat'),/较大/);
});
test('upscale implementation and UI procedure are byte-for-byte preserved',()=>{
  // SHA256 of the supplied 0.3.6 function blocks, normalizing checkout line endings to LF.
  const hash=s=>crypto.createHash('sha256').update(s.replace(/\r\n/g,'\n')).digest('hex');
  const logic=source.slice(source.indexOf('  function pngSize('),source.indexOf('  // Vibe encoding'));
  assert.equal(hash(logic),'b18370d748b57d3dc1d8e434c1caaab93a43ecd16453676eef3ec7ea42e986c7');
  const ui=fs.readFileSync(path.join(__dirname,'../ui/index.html'),'utf8');
  const uiBlock=ui.slice(ui.indexOf('async function upscaleImage('),ui.indexOf("$('upscaleInput').onclick"));
  assert.equal(hash(uiBlock),'a11cef1e97cec0cabe9546e0465462e89667aced9e30f3fbd4f7023d7b08ff9f');
});

test('preset rename and removal update every saved reference atomically',()=>{
  const h=harness(), api=h.context.NAI;
  let s=api.initial();
  s.defaults.i2i_style=s.defaults.style;
  s.defaults.negative='基础负面';s.defaults.i2i_negative='基础负面';
  s.presets.characters.A={text:'a person'};s.defaults.characters=['A'];
  const original=JSON.stringify(s);
  let n=api.editPreset(s,'styles','基础插画','New style',{text:'new art',negative:'基础负面'});
  assert.equal(n.defaults.style,'New style');assert.equal(n.defaults.i2i_style,'New style');
  assert.equal(JSON.stringify(s),original);
  n=api.editPreset(n,'negatives','基础负面','New negative',{text:'low quality'});
  assert.equal(n.defaults.negative,'New negative');assert.equal(n.defaults.i2i_negative,'New negative');
  assert.equal(n.presets.styles['V5二次元版'].negative,'New negative');
  n=api.editPreset(n,'characters','A','B',{text:'another person'});
  assert.equal(n.defaults.characters.join(','),'B');
  n=api.editPreset(n,'negatives','New negative','',null);
  assert.equal(n.defaults.negative,'');assert.equal(n.presets.styles['New style'].negative,'');
  n=api.editPreset(n,'characters','B','',null);
  n=api.editPreset(n,'styles','New style','',null);
  assert.equal(n.defaults.characters.length,0);assert.equal(n.defaults.style,'');assert.equal(n.defaults.i2i_style,'');
  h.store.set('nai.studio.v2',JSON.stringify(n));assert.equal(api.status().success,true);
  assert.doesNotThrow(()=>api.plan(n,'a small cat',{}));
});

test('preset collisions, prototype names and dangling pairs fail without mutation',()=>{
  const api=harness().context.NAI,s=api.initial(),before=JSON.stringify(s);
  for(const args of [
    ['styles','基础插画','V5二次元版',{text:'overwrite'}],
    ['styles','','基础插画',{text:'overwrite'}],
    ['styles','','__proto__',{text:'unsafe'}],
    ['styles','','Bad pair',{text:'art',negative:'missing'}]
  ]) assert.throws(()=>api.editPreset(s,...args));
  assert.equal(JSON.stringify(s),before);
  const invalid=api.copy(s);invalid.presets.styles.unused={text:7};
  assert.throws(()=>api.validate(invalid.defaults,invalid.presets));
});

function pageHelpers(h) {
  const ui=fs.readFileSync(path.join(__dirname,'../ui/index.html'),'utf8');
  h.context.newId=()=>'preview_test_001';
  h.context.tool=(name,p)=>h.context.exports[name](p);
  vm.runInContext(ui.slice(ui.indexOf('function drawOverrides('),ui.indexOf("$('preview').onclick")),h.context);
}

test('page img2img preview and submission preserve explicit seed and safe reference state',async()=>{
  const h=harness();pageHelpers(h);
  const d={...h.context.NAI.initial().defaults,transport:'native',mode:'img2img',image_id:'base_image',seed:123456};
  const over=h.context.drawOverrides(d), preview=await h.context.previewDraw('a small cat',over);
  assert.equal(preview.effective.seed,123456);assert.equal(preview.request.parameters.seed,123456);
  assert.equal(h.calls.length,0);assert.equal(h.store.has('nai.studio.v2'),false);
  const result=await h.tool('nai_draw',{prompt:'a small cat',request_id:'page_draw_001',overrides:JSON.stringify(over)});
  assert.equal(result.success,true);assert.equal(h.calls[0].body.parameters.seed,123456);
  assert.equal(h.calls[0].body.parameters.strength,preview.request.parameters.strength);
});

test('page URL preview uses supported fields and reference preview reports unencoded material',async()=>{
  const h=harness();pageHelpers(h);
  const d=h.context.NAI.initial().defaults,over=h.context.drawOverrides(d);
  assert.equal('seed' in over,false);assert.equal('steps' in over,false);
  assert.equal((await h.context.previewDraw('a small cat',over)).success,true);
  const native={...d,...defaults};
  const preview=await h.context.previewDraw('a small cat',h.context.drawOverrides(native));
  assert.equal(preview.references.vibe[0].encoding_status,'not_encoded');
  assert.equal(h.calls.length,0);assert.equal(h.store.has('nai.job.preview_test_001'),false);
});

test('malformed successful responses never become successful local images or repost',async()=>{
  for(const bytes of ['',Buffer.from('not an image at all').toString('base64'),png+'!',png.slice(0,-3)]) {
    const h=harness();h.context.response={ok:true,status:200,json:()=>({images:[{base64:bytes}]})};
    const args=draw({vibe_enabled:false}),r=await h.tool('nai_draw',args);
    assert.equal(r.status,'response_unreadable');assert.equal(r.success,false);
    assert.equal(h.store.has('nai.asset.draw_test_001'),false);
    await h.tool('nai_draw',args);assert.equal(h.calls.length,1);
  }
});

test('valid unpadded and data-URL images preserve original decoded bytes',async()=>{
  for(const bytes of [png.replace(/=+$/,''),'data:image/png;base64,'+png]) {
    const h=harness();h.context.response={ok:true,status:200,json:()=>({images:[{base64:bytes}]})};
    assert.equal((await h.tool('nai_draw',draw({vibe_enabled:false}))).success,true);
    assert.equal(h.store.get('nai.asset.draw_test_001.0'),png);
  }
});

test('failed result chunk writes and missing readback never report success or repost',async()=>{
  for(const mode of ['false','missing']) {
    const h=harness(),original=h.context.dataStore.set;
    h.context.dataStore.set=(k,v)=>k==='nai.asset.draw_test_001.0' ? (mode==='false'?false:true) : original(k,v);
    const args=draw({vibe_enabled:false}),r=await h.tool('nai_draw',args);
    assert.equal(r.status,'response_unreadable');assert.equal(r.success,false);
    assert.equal(h.store.has('nai.asset.draw_test_001'),false);
    await h.tool('nai_draw',args);assert.equal(h.calls.length,1);
  }
});

test('UI rejects string-encoded failure replies and missing persistent readback',async()=>{
  const ui=fs.readFileSync(path.join(__dirname,'../ui/index.html'),'utf8');
  const context={Bridge:{setData:async()=>'{"success":false}',getData:async()=>'{}'}};
  vm.createContext(context);
  vm.runInContext(ui.slice(ui.indexOf('function parse('),ui.indexOf('async function safe(')),context);
  await assert.rejects(()=>context.set('key',{}),/保存失败/);
  context.Bridge.setData=async()=>({success:true});context.Bridge.getData=async()=>null;
  await assert.rejects(()=>context.set('key',{}),/保存失败/);
});
