const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const script = fs.readFileSync('app/src/main/assets/weibo_handoff_probe.js', 'utf8');
function harness(host='api.weibo.com', path='/oauth2/authorize', readyAt=3, succeeds=true){
  let count=0, clicks=0, timer=null;
  const window={};
  const context={window,location:{hostname:host,pathname:path,protocol:'https:'},
    document:{getElementById(){return count>=readyAt?{click(){clicks++;if(succeeds) window.nmlWeiboHandoffIssued=true;}}:null;}},
    setTimeout(fn){timer=fn;}, clearTimeout(){timer=null;}};
  vm.runInNewContext(script, context);
  const drain=()=>{while(timer&&count<100){const fn=timer;timer=null;count++;fn();}};
  drain();
  return {window,context,get clicks(){return clicks;},get count(){return count;},drain};
}
let h=harness();assert.equal(h.clicks,1);
vm.runInNewContext(script,h.context);h.drain();assert.equal(h.clicks,1,'repeated finish does not relaunch');
assert.equal(harness('untrusted.example').clicks,0);
assert.equal(harness('api.weibo.com','/unrelated').clicks,0);
h=harness('api.weibo.com','/oauth2/authorize',999);assert.equal(h.clicks,0);assert.ok(h.count<=60);
h=harness('api.weibo.com','/oauth2/authorize',0,false);assert.equal(h.clicks,1,'an unobserved native return must not cause repeated opens');
console.log('Weibo handoff probe contracts passed');
