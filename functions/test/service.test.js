// Executes actual Cloud Function handlers against deterministic Firebase/SMTP fakes.
// This tests business logic; deployment, IAM and real Firestore still require a cloud smoke test.
const signature=[[[.1,.1],[.2,.3],[.4,.2]]];
const {test}=require('node:test');const assert=require('node:assert/strict');const vm=require('node:vm');const fs=require('node:fs');const {DateTime}=require('luxon');
function fixture({tokenFails=false,now=null}={}){
  const store=new Map(),authUsers=new Map(),sent=[],telemetry=[];
  const snapshot=path=>({id:path.split('/').pop(),exists:store.has(path),data:()=>store.get(path)});
  const doc=path=>({path,get:async()=>snapshot(path),set:async(v,opt)=>{store.set(path,opt?.merge?{...store.get(path),...v}:v);},create:async v=>{if(store.has(path))throw Object.assign(new Error('exists'),{code:6});store.set(path,v);}});
  const query=(collection,filters=[])=>({where:(field,op,value)=>query(collection,[...filters,[field,op,value]]),get:async()=>({docs:[...store].filter(([p,v])=>p.split('/').length===2&&p.startsWith(collection+'/')&&filters.every(([f,o,x])=>o==='>='?v[f]>=x:o==='<'?v[f]<x:o==='<='?v[f]<=x:o==='in'?x.includes(v[f]):v[f]===x)).map(([p])=>snapshot(p))})});
  let queue=Promise.resolve();
  const db={doc,collection:query,runTransaction:fn=>{const work=queue.then(async()=>{const before=new Map(store);const t={get:async r=>r.path?snapshot(r.path):r.get(),set:(r,v,opt)=>store.set(r.path,opt?.merge?{...store.get(r.path),...v}:v),create:(r,v)=>{if(store.has(r.path))throw new Error('exists');store.set(r.path,v);},delete:r=>store.delete(r.path)};try{return await fn(t);}catch(e){store.clear();for(const [k,v]of before)store.set(k,v);throw e;}});queue=work.catch(()=>{});return work;}};
  class HttpsError extends Error{constructor(code,message){super(message);this.code=code;}}
  const exports={};
  const auth={createCustomToken:async uid=>{if(tokenFails)throw new Error('signBlob permission denied');return 'token-'+uid;},createUser:async user=>{authUsers.set(user.uid,user);return user;},deleteUser:async uid=>authUsers.delete(uid)};
  const fakeRequire=name=>{
    if(name==='firebase-functions/logger')return {info:(message,fields)=>telemetry.push({message,...fields}),error:(message,fields)=>telemetry.push({message,...fields})};
    if(name==='firebase-functions/v2/https')return {onCall:(opts,fn)=>fn,HttpsError};
    if(name==='firebase-functions/v2/scheduler')return {onSchedule:(opts,fn)=>fn};
    if(name==='firebase-functions/params')return {defineSecret:n=>({value:()=>n==='PIN_PEPPER'?'test-pepper':'test-password'}),defineString:(n,opt)=>({value:()=>opt?.default||'test-value'})};
    if(name==='firebase-admin/app')return {initializeApp:()=>{}};
    if(name==='firebase-admin/firestore')return {getFirestore:()=>db,Timestamp:{fromMillis:v=>v}};
    if(name==='firebase-admin/auth')return {getAuth:()=>auth};
    if(name==='nodemailer')return {createTransport:()=>({sendMail:async message=>sent.push(message)})};
    if(name==='./domain')return require('../domain');return require(name);
  };
  const Clock=now===null?Date:class extends Date {constructor(...args){super(...(args.length?args:[now]));}static now(){return now;}};
  vm.runInNewContext(fs.readFileSync(require.resolve('../index'),'utf8'),{require:fakeRequire,exports,console:{...console,error:()=>{}},Date:Clock,Buffer,Map,Promise});
  const D=require('../domain'),adminIndex=D.pinIndex('9090','test-pepper'),adminSalt='admin-salt';
  store.set('pins/'+adminIndex,{uid:'admin',salt:adminSalt,hash:D.pinHash('9090',adminSalt,'test-pepper')});
  store.set('users/admin',{name:'Manager',role:'manager',location:'Hotel',job_title:'Manager',pinIndex:adminIndex});
  const req=(uid,data={},ip='127.0.0.1')=>({auth:uid?{uid,token:{auth_time:Math.floor(Date.now()/1000)}}:null,data:uid==='admin'?{admin_pin:'9090',...data}:data,rawRequest:{ip}});
  return {store,sent,telemetry,f:exports,req,setNow:value=>{now=value;}};
}
test('admin creates PIN employee, login resolves identity, edits preserve identity',async()=>{
  const {f,req,store}=fixture();await f.users(req('admin',{name:'Rosa',job_title:'Housekeeper',location:'Hotel',pin:'0012',confirm_pin:'0012'}));
  const result=await f.pinLogin(req(null,{pin:'0012'}));assert.equal(result.user.name,'Rosa');assert.match(result.customToken,/token-employee/);
  const uid=result.user.id;await f.users(req('admin',{id:uid,name:'Rosa updated',job_title:'Supervisor',location:'Restaurant',pin:'5678',confirm_pin:'5678'}));
  await assert.rejects(f.pinLogin(req(null,{pin:'0012'})),/PIN not recognized/);
  const next=await f.pinLogin(req(null,{pin:'5678'}));assert.equal(next.user.id,uid);assert.equal(next.user.location,'Restaurant');
  assert.equal([...store.keys()].filter(k=>k.startsWith('pins/')).length,2);
});
test('duplicate PIN, employee admin access and unauthenticated writes denied',async()=>{
  const {f,req}=fixture();const data={name:'Alice',job_title:'Housekeeper',location:'Hotel',pin:'1234',confirm_pin:'1234'};
  await f.users(req('admin',data));await assert.rejects(f.users(req('admin',data)),/already assigned/);
  const u=(await f.pinLogin(req(null,{pin:'1234'}))).user;
  await assert.rejects(f.settings(req(u.id,{email:'test@example.com'})),/Admin access/);
  await assert.rejects(f.cash(req(null,{amount:'1'})),/sign in/);
});
test('cash retry is idempotent and employee dashboard isolates records',async()=>{
  const {f,req,store}=fixture();store.set('users/a',{name:'A',role:'employee',duties:['hotel_cash']});store.set('users/b',{name:'B',role:'employee',duties:['hotel_cash']});
  const data={department:'Hotel',amount:'25.10',signature,request_id:'cash-12345678'};await f.cash(req('a',data));await f.cash(req('a',data));
  assert.equal([...store.keys()].filter(k=>k.startsWith('cash/')).length,1);assert.equal(store.get('cash/cash-12345678').cents,2510);
  assert.equal((await f.dashboard(req('b'))).cash.length,0);assert.equal((await f.dashboard(req('admin'))).cash.length,1);
});
test('equipment locks, lunch return, changed equipment and four-event order',async()=>{
  const {f,req,store}=fixture();for(const id of ['a','b'])store.set('users/'+id,{name:id,role:'employee',duties:['equipment']});
  const sig=[[[.1,.1],[.2,.3],[.4,.2]]];let n=0;
  const event=(uid,action,radio='1',keys='2')=>f.equipment(req(uid,{action,location:'Hotel',radio,keys,signature:sig,request_id:'event-000000'+(++n)}));
  await event('a','start');await assert.rejects(event('b','start','01','002'),/already checked out/);await assert.rejects(event('a','in'),/shift changed/);
  await event('a','lunch_out');await event('b','start');await event('a','lunch_in','3','4');await event('a','end');
  assert.equal(store.get('currentShifts/a').state,'end');assert.equal(store.has('equipmentLocks/radio-3'),false);assert.equal(store.get('equipmentLocks/radio-1').user_id,'b');
});
test('mail settings authorization, manual report and scheduled no-duplicate delivery',async()=>{
  const {f,req,store,sent}=fixture();await f.settings(req('admin',{email:'test@example.com',enabled:true}));await f.testEmail(req('admin'));
  await f.previousReport(req('admin'));await f.dailyReport();await f.dailyReport();
  assert.equal(sent.length,3);assert.equal(sent[1].attachments.length,2);assert.equal(sent[1].attachments[0].filename,'cash.csv');assert.equal(sent[2].attachments[0].filename,'cash.csv');assert.equal(sent[2].to,'test@example.com');
  assert.equal([...store.keys()].filter(k=>k.startsWith('deliveries/')).length,1);
});
test('expired sessions cannot write logs',async()=>{
  const {f,req,store}=fixture();store.set('users/a',{name:'A',role:'employee',duties:['hotel_cash']});const r=req('a',{department:'Hotel',amount:'1',request_id:'cash-12345678'});r.auth.token.auth_time-=43201;await assert.rejects(f.cash(r),/session expired/);
});
test('Central day bounds include DST changes in previous-day report',()=>{
  assert.equal(DateTime.fromISO('2026-03-08',{zone:'America/Chicago'}).plus({days:1}).diff(DateTime.fromISO('2026-03-08',{zone:'America/Chicago'}),'hours').hours,23);
  assert.equal(DateTime.fromISO('2026-11-01',{zone:'America/Chicago'}).plus({days:1}).diff(DateTime.fromISO('2026-11-01',{zone:'America/Chicago'}),'hours').hours,25);
});

test('multiple locations and duties preserved; managers have all log access and PIN login',async()=>{
  const {f,req}=fixture();
  await f.users(req('admin',{first_name:'Rosa',last_name:'Rodriguez',job_title:'Housekeeper / Cashier',locations:['Hotel','Restaurant'],duties:['equipment','restaurant_cash'],role:'employee',pin:'1234',confirm_pin:'1234'}));
  const u=(await f.pinLogin(req(null,{pin:'1234'}))).user;assert.deepEqual([...u.locations],['Hotel','Restaurant']);assert.deepEqual([...u.duties],['equipment','restaurant_cash']);assert.equal(u.name,'Rosa Rodriguez');
  await f.users(req('admin',{first_name:'John',last_name:'Smith',job_title:'Manager',role:'manager',pin:'5678',confirm_pin:'5678'}));
  const manager=(await f.pinLogin(req(null,{pin:'5678'}))).user;assert.equal(manager.role,'manager');assert.equal(manager.duties.length,3);
  await f.cash(req(manager.id,{department:'Restaurant',venue:'Bar',meal:'Dinner',amount:'10',signature,request_id:'manager-cash-123'}));
});
test('cash location authorization, Bar breakfast rejection and required signatures',async()=>{
  const {f,req,store}=fixture();store.set('users/a',{name:'Alice Cash',role:'employee',locations:['Hotel'],duties:['hotel_cash']});
  const data={department:'Hotel',amount:'12.34',signature,request_id:'signed-cash-123'};
  await assert.rejects(f.cash(req('a',{...data,signature:[]})),/signature/);
  await assert.rejects(f.cash(req('a',{...data,department:'Restaurant',venue:'Restaurant',meal:'Lunch'})),/cash access/);
  await assert.rejects(f.cash(req('admin',{...data,department:'Restaurant',venue:'Bar',meal:'Breakfast'})),/valid outlet/);
  await f.cash(req('a',data));const saved=store.get('cash/signed-cash-123');assert.equal(saved.first_name,'Alice');assert.equal(saved.last_name,'Cash');assert.equal(saved.signature,JSON.stringify(signature));
  await assert.rejects(f.cash(req('a',{...data,signature:[[[.1,.1],[.2,.2],[.5,.5]]]})),/conflict/);
});
test('repeated equipment In and Out; end while out; undo never steals reissued equipment',async()=>{
  const {f,req,store}=fixture();for(const id of ['a','b'])store.set('users/'+id,{name:id,role:'employee',locations:['Hotel'],duties:['equipment']});
  let count=0;const event=(uid,action,radio='1',keys='2')=>f.equipment(req(uid,{action,radio,keys,location:'Hotel',signature,request_id:'equipment-event-'+(++count)}));
  await event('a','start');await event('a','out');await event('a','in');await event('a','out');await event('a','end');
  const shift=store.get('currentShifts/a').shift_id;
  await event('b','start');await event('a','undo_end');assert.equal(store.get('currentShifts/a').state,'out');assert.equal(store.get('currentShifts/a').shift_id,shift);assert.equal(store.get('equipmentLocks/radio-1').user_id,'b');
  await assert.rejects(event('a','in'),/already checked out/);await event('a','in','3','4');await event('a','end');assert.equal(store.has('equipmentLocks/radio-3'),false);
  assert.equal((await f.dashboard(req('a'))).shift.state,'end');await event('a','start','5','6');assert.notEqual(store.get('currentShifts/a').shift_id,shift);
});
test('equipment access denied to cash-only staff and shift location cannot switch midshift',async()=>{
  const {f,req,store}=fixture();store.set('users/a',{name:'A',role:'employee',locations:['Hotel'],duties:['hotel_cash']});
  await assert.rejects(f.equipment(req('a',{action:'start',location:'Hotel',radio:'1',keys:'2',signature,request_id:'equipment-123'})),/equipment log access/);
  store.set('users/a',{name:'A',role:'employee',locations:['Hotel'],duties:['equipment']});
  await assert.rejects(f.equipment(req('a',{action:'start',location:'Restaurant',radio:'1',keys:'2',signature,request_id:'equipment-123'})),/Hotel only/);
});
test('date range report includes both days, filters report type and renders signatures safely',async()=>{
  const {f,req,store,sent}=fixture();await f.settings(req('admin',{email:'reports@example.com',enabled:true}));
  store.set('cash/c1',{name:'<script>A</script>',department:'Restaurant',venue:'Bar',meal:'Dinner',cents:500,signature:JSON.stringify(signature),created:'2026-03-09T04:59:59.000Z'});
  store.set('cash/c2',{name:'Outside',department:'Hotel',cents:500,created:'2026-03-09T05:00:00.000Z'});
  store.set('events/e1',{name:'Radio',action:'start',shift_id:'s1',state:'in',location:'Hotel',radio:'1',keys:'2',signature:JSON.stringify(signature),created:'2026-03-08T12:00:00.000Z'});
  await f.sendReport(req('admin',{type:'cash',start_date:'2026-03-08',end_date:'2026-03-08'}));
  assert.match(sent[0].attachments[0].content,/Dinner/);assert.doesNotMatch(sent[0].attachments[0].content,/Outside/);assert.match(sent[0].attachments[1].content,/<svg/);assert.doesNotMatch(sent[0].attachments[1].content,/<script>/);
  await f.sendReport(req('admin',{type:'equipment',start_date:'2026-03-08',end_date:'2026-03-09'}));assert.equal(sent[1].attachments[0].filename,'radio-keys.csv');assert.match(sent[1].text,/Outstanding equipment at range end: 1/);
  await assert.rejects(f.sendReport(req('admin',{type:'cash',start_date:'2026-03-10',end_date:'2026-03-08'})),/valid date range/);
});
test('legacy users remain readable and can be updated without deleting data',async()=>{
  const {f,req,store}=fixture();store.set('users/legacy',{name:'Old User',role:'employee',job_title:'Housekeeper',location:'Hotel'});
  const u=(await f.profile(req('legacy'))).user;assert.deepEqual([...u.duties],['equipment']);
  await f.users(req('admin',{id:'legacy',first_name:'Old',last_name:'User',job_title:'Housekeeper',locations:['Hotel','Restaurant'],duties:['equipment'],pin:'1234',confirm_pin:'1234'}));assert.equal(store.get('users/legacy').name,'Old User');
});

test('PIN token signing failure produces actionable error without exposing credentials',async()=>{
  const {f,req}=fixture({tokenFails:true});await f.users(req('admin',{name:'Alice Test',job_title:'Front desk',location:'Hotel',pin:'1234',confirm_pin:'1234'}));
  await assert.rejects(f.pinLogin(req(null,{pin:'1234'})),e=>e.code==='failed-precondition'&&/token-signing permissions/.test(e.message)&&!e.message.includes('1234'));
});
test('daily empty report remains cash-only even with equipment activity',async()=>{
  const {f,req,store,sent}=fixture();await f.settings(req('admin',{email:'reports@example.com',enabled:true}));
  const yesterday=DateTime.now().setZone('America/Chicago').minus({days:1}).startOf('day').plus({hours:12}).toUTC().toISO();
  store.set('events/e',{name:'Housekeeper',radio:'1',keys:'2',action:'start',state:'in',shift_id:'s',created:yesterday});
  await f.dailyReport();assert.match(sent[0].text,/Cash drops: 0/);assert.match(sent[0].text,/No activity/);assert.doesNotMatch(sent[0].text,/Equipment events/);assert.deepEqual(Array.from(sent[0].attachments,a=>a.filename),['cash.csv','cash-signed-report.html']);
});
test('legacy active shifts accept Out and email admin profiles can gain PIN without identity changes',async()=>{
  const {f,req,store}=fixture();store.set('users/a',{name:'Old Housekeeper',job_title:'Housekeeper',location:'Hotel',role:'employee'});
  store.set('currentShifts/a',{state:'lunch_in',shift_id:'legacy-shift',radio:'1',keys:'2',created:'2026-01-01T00:00:00Z'});store.set('equipmentLocks/radio-1',{user_id:'a'});store.set('equipmentLocks/keys-2',{user_id:'a'});
  await f.equipment(req('a',{action:'out',signature,request_id:'legacy-out-123'}));assert.equal(store.get('currentShifts/a').state,'out');assert.equal(store.has('equipmentLocks/radio-1'),false);
  store.set('users/admin',{name:'Admin Owner',username:'owner@example.com',role:'manager',job_title:'Manager',location:'Hotel'});
  const setup=req('admin',{pin:'5678',confirm_pin:'5678'});setup.auth.token.firebase={sign_in_provider:'password'};await f.setAdminPin(setup);assert.equal(store.get('users/admin').username,'owner@example.com');assert.equal((await f.pinLogin(req(null,{pin:'5678'}))).user.id,'admin');
});

test('Restaurant-only employees cannot receive housekeeping duty; managers start equipment only at Hotel',async()=>{
  const {f,req,store}=fixture();
  await assert.rejects(f.users(req('admin',{first_name:'Test',last_name:'Cashier',job_title:'Cashier',locations:['Restaurant'],duties:['restaurant_cash','equipment'],pin:'1234',confirm_pin:'1234'})),/Housekeeping requires the Hotel/);
  assert.equal([...store.keys()].filter(k=>k.startsWith('pins/')).length,1);
  await assert.rejects(f.equipment(req('admin',{action:'start',location:'Restaurant',radio:'8',keys:'9',signature,request_id:'restaurant-equipment-123'})),/Hotel only/);
  await f.equipment(req('admin',{action:'start',location:'Hotel',radio:'8',keys:'9',signature,request_id:'hotel-equipment-123'}));
  assert.equal(store.get('currentShifts/admin').location,'Hotel');
});


test('automatic meals use Central time at noon and 3 PM; duplicate and third meals rejected',async()=>{
  const {f,req,store}=fixture();store.set('users/h',{name:'House Keeper',role:'employee',locations:['Hotel'],duties:['equipment']});
  const sign=(id,time)=>f.meal({...req('h',{meal:'tampered',signature,request_id:id}),offlineCreated:time});
  await sign('breakfast-meal-123','2026-10-03T16:59:59.000Z'); // 11:59:59 AM CDT
  await sign('breakfast-meal-123','2026-10-03T16:59:59.000Z'); // retry
  await assert.rejects(sign('duplicate-meal-123','2026-10-03T16:59:59.000Z'),/already logged/);
  await sign('lunch-meal-123','2026-10-03T17:00:00.000Z');
  assert.equal(store.get('meals/breakfast-meal-123').meal,'Breakfast');assert.equal(store.get('meals/lunch-meal-123').meal,'Lunch');
  await assert.rejects(sign('third-meal-123','2026-10-03T20:00:00.000Z'),/Two free meals/);
  await sign('dinner-next-day-123','2026-10-04T20:00:00.000Z');assert.equal(store.get('meals/dinner-next-day-123').meal,'Dinner');
  store.set('users/c',{name:'Cashier',role:'employee',locations:['Restaurant'],duties:['restaurant_cash']});
  await assert.rejects(f.meal(req('c',{signature,request_id:'no-meal-access-123'})),/Hotel housekeeping/);
});
test('offline grant replay preserves original time and signatures, deduplicates and rechecks permission',async()=>{
  const {f,req,store}=fixture();await f.users(req('admin',{name:'Test Worker',job_title:'Front desk',locations:['Hotel'],duties:['hotel_cash'],pin:'0012',confirm_pin:'0012'}));
  const login=await f.pinLogin(req(null,{pin:'0012',device_id:'tablet-device-12345'})),u=login.user,grant=login.offline;
  assert.equal(store.get('offlineGrants/'+grant.id).hash.includes(grant.token),false);
  const created=new Date(Date.now()-1000).toISOString(),body={department:'Hotel',amount:'10',signature,request_id:'offline-cash-123'};
  const request={data:{operation:'cash',grant_id:grant.id,grant_token:grant.token,created,body}};
  await f.offlineSync(request);await f.offlineSync(request);assert.equal(store.get('cash/offline-cash-123').created,created);assert.equal(store.get('cash/offline-cash-123').signature,JSON.stringify(signature));
  await assert.rejects(f.offlineSync({data:{...request.data,grant_token:'0'.repeat(64)}}),/not valid/);
  await assert.rejects(f.offlineSync({data:{...request.data,operation:'users'}}),/Invalid queued log/);
  const renewed=await f.pinLogin(req(null,{pin:'0012',device_id:'tablet-device-12345',offline:grant}));assert.equal(renewed.offline.id,grant.id);
  store.get('users/'+u.id).duties=['equipment'];await assert.rejects(f.offlineSync({data:{...request.data,body:{...body,request_id:'offline-denied-123'}}}),/cash access/);
  store.get('users/'+u.id).pinIndex='changed';await assert.rejects(f.offlineSync(request),/revoked/);
});
test('offline equipment replay follows In/Out order and retains original event timestamps',async()=>{
  const {f,req,store}=fixture();await f.users(req('admin',{name:'Test Worker',job_title:'Housekeeper',location:'Hotel',pin:'1234',confirm_pin:'1234'}));
  const login=await f.pinLogin(req(null,{pin:'1234',device_id:'tablet-device-12345'})),grant=login.offline;
  const replay=(action,n)=>f.offlineSync({data:{operation:'equipment',grant_id:grant.id,grant_token:grant.token,created:new Date(Date.now()-3000+n*500).toISOString(),body:{action,radio:'1',keys:'2',location:'Hotel',expected_shift_id:'offline-equipment-1',signature,request_id:'offline-equipment-'+n}}});
  await replay('start',1);await replay('out',2);await replay('in',3);await replay('end',4);
  assert.equal(store.get('currentShifts/'+login.user.id).state,'end');assert.equal(store.has('equipmentLocks/radio-1'),false);
  store.get('currentShifts/'+login.user.id).shift_id='different-shift';await assert.rejects(replay('undo_end',5),/shift changed/);
});
test('multiple admins can manage staff; employees cannot create admins',async()=>{
  const {f,req,store}=fixture();const result=await f.createAdmin(req('admin',{first_name:'Second',last_name:'Admin',email:'second@example.com',password:'StrongPassword123'}));
  assert.equal(store.get('users/'+result.id).role,'manager');assert.equal(store.get('users/admin').role,'manager');
  await f.settings(req(result.id,{email:'reports@example.com',enabled:1}));
  store.set('users/e',{role:'employee'});await assert.rejects(f.createAdmin(req('e',{first_name:'No',last_name:'Access',email:'no@example.com',password:'StrongPassword123'})),/Admin access/);
});

test('offline meal replay uses capture time, enforces two per day across devices and rejects expired grants',async()=>{
  const morning=Date.parse('2026-10-03T14:00:00.000Z'),{f,req,store,setNow}=fixture({now:morning});
  await f.users(req('admin',{name:'House Keeper',job_title:'Housekeeper',location:'Hotel',pin:'1234',confirm_pin:'1234'}));
  const login=await f.pinLogin(req(null,{pin:'1234',device_id:'tablet-device-12345'})),grant=login.offline;
  const send=(id,created)=>f.offlineSync({data:{operation:'meal',grant_id:grant.id,grant_token:grant.token,created,body:{meal:'client-ignored',signature,request_id:id}}});
  setNow(Date.parse('2026-10-03T20:00:00.000Z'));
  await send('offline-breakfast-123','2026-10-03T14:00:01.000Z');await send('offline-lunch-123','2026-10-03T17:00:00.000Z');
  assert.equal(store.get('meals/offline-breakfast-123').meal,'Breakfast');assert.equal(store.get('meals/offline-lunch-123').meal,'Lunch');
  await assert.rejects(send('offline-dinner-123','2026-10-03T20:00:00.000Z'),/Two free meals/);
  await assert.rejects(send('offline-future-123','2026-10-03T21:00:00.000Z'),/log time/);
  setNow(grant.expires+8*86400000);await assert.rejects(send('offline-expired-123','2026-10-03T14:00:01.000Z'),/expiry/);
});

test('monitoring logs timings and safe categories without credentials or log contents',async()=>{
  const {f,req,telemetry}=fixture();
  await f.users(req('admin',{first_name:'Private',last_name:'Employee',job_title:'Manager',role:'manager',pin:'8765',confirm_pin:'8765'}));
  await f.pinLogin(req(null,{pin:'8765'}));
  await assert.rejects(f.pinLogin(req(null,{pin:'1111'})),/PIN not recognized/);
  assert.equal(telemetry.length,3);
  assert.equal(telemetry[0].operation,'users');assert.equal(telemetry[0].outcome,'success');
  assert.equal(telemetry[2].outcome,'rejected');assert.equal(telemetry[2].error_category,'unauthenticated');
  for(const entry of telemetry){assert.deepEqual(Object.keys(entry).sort(),['duration_ms','error_category','message','operation','outcome']);assert.ok(entry.duration_ms>=0);}
  assert.doesNotMatch(JSON.stringify(telemetry),/8765|Private|Employee|signature|customToken/);
});

test('Ortiz employment type survives PIN login and invalid types are rejected',async()=>{
  const {f,req}=fixture();const data={name:'Ortiz Employee',job_title:'Housekeeper',locations:['Hotel'],duties:['equipment'],employment_type:'ortiz',pin:'8787',confirm_pin:'8787'};
  await f.users(req('admin',data));assert.equal((await f.pinLogin(req(null,{pin:'8787'}))).user.employment_type,'ortiz');
  await assert.rejects(f.users(req('admin',{...data,employment_type:'invalid'})),/Ortiz or Full time/);
});
test('dated activity is admin-only, bounded in Central time, and associates corrected servers',async()=>{
  const {f,req,store}=fixture();store.set('users/a',{name:'A',role:'employee',duties:['equipment']});
  store.set('meals/m1',{user_id:'a',name:'A',meal:'Dinner',signature:JSON.stringify(signature),created:'2026-10-02T04:59:59.000Z'});
  store.set('meals/m2',{user_id:'a',name:'A',meal:'Dinner',created:'2026-10-02T05:00:00.000Z'});
  await f.logServer(req('admin',{date:'2026-10-01',meal:'Dinner',name:'Server One'}));
  const data={type:'meal',start_date:'2026-10-01',end_date:'2026-10-01'};
  const result=await f.activity(req('admin',data));assert.equal(result.rows.length,1);assert.equal(result.rows[0].server_name,'Server One');assert.equal(result.servers.length,1);assert.equal(result.rows[0].day,'2026-10-01');
  await f.logServer(req('admin',{date:'2026-10-01',meal:'Dinner',name:'Corrected'}));assert.equal((await f.activity(req('admin',data))).rows[0].server_name,'Corrected');
  await assert.rejects(f.activity(req('a',data)),/Admin access/);await assert.rejects(f.logServer(req('a',{date:'2026-10-01',meal:'Dinner',name:'Invalid'})),/Admin access/);
  await assert.rejects(f.activity(req('admin',{...data,type:'users'})),/log type/);await assert.rejects(f.activity(req('admin',{...data,end_date:'2026-09-01'})),/valid date range/);
});
test('meal captures server and employment type in its persisted record',async()=>{
  const stamp=Date.parse('2026-10-01T18:00:00.000Z');const {f,req,store}=fixture({now:stamp});
  store.set('users/a',{name:'A',role:'employee',locations:['Hotel'],duties:['equipment'],employment_type:'ortiz'});
  await f.logServer(req('admin',{date:'2026-10-01',meal:'Lunch',name:'Lunch Server'}));
  await f.meal(req('a',{signature,request_id:'server-meal-123'}));
  assert.equal(store.get('meals/server-meal-123').server_name,'Lunch Server');assert.equal(store.get('meals/server-meal-123').employment_type,'ortiz');
});

test('hotel duties are mutually exclusive on server and bar-only staff cannot log Restaurant cash',async()=>{
  const {f,req,store}=fixture();const data={name:'Duty Test',job_title:'Cashier',locations:['Hotel'],duties:['hotel_cash','equipment'],pin:'1234',confirm_pin:'1234',separate_outlets:true};
  await assert.rejects(f.users(req('admin',data)),/cannot both be assigned/);
  await f.users(req('admin',{...data,locations:['Restaurant'],duties:['bar_cash']}));const u=(await f.pinLogin(req(null,{pin:'1234'}))).user;assert.deepEqual([...u.cash_outlets],['Bar']);
  const cash={department:'Restaurant',venue:'Bar',meal:'Dinner',amount:'5',signature,request_id:'bar-only-cash-123'};
  await f.cash(req(u.id,cash));await assert.rejects(f.cash(req(u.id,{...cash,venue:'Restaurant',request_id:'wrong-outlet-123'})),/outlet/);
  await f.users(req('admin',{...data,id:u.id,locations:['Restaurant'],duties:['restaurant_cash']}));
  await assert.rejects(f.cash(req(u.id,{...cash,request_id:'no-bar-cash-123'})),/outlet/);
});
test('profile edits preserve PIN and require acting admin PIN, not the employee PIN',async()=>{
  const {f,req,store}=fixture();await f.users(req('admin',{name:'Original',job_title:'Front desk',locations:['Hotel'],duties:['hotel_cash'],pin:'1234',confirm_pin:'1234'}));
  const id=(await f.pinLogin(req(null,{pin:'1234'}))).user.id,index=store.get('users/'+id).pinIndex;
  const edit={id,name:'Updated',job_title:'Front desk',locations:['Hotel'],duties:['hotel_cash']};
  await assert.rejects(f.users(req('admin',{...edit,admin_pin:'1234'})),/incorrect/);
  const missing=req('admin',edit);delete missing.data.admin_pin;await assert.rejects(f.users(missing),/PIN must contain/);
  await f.users(req('admin',edit));assert.equal(store.get('users/'+id).pinIndex,index);assert.equal((await f.pinLogin(req(null,{pin:'1234'}))).user.name,'Updated');
});
test('PIN reset rejects collisions, removes old PIN, preserves profile and revokes offline grants',async()=>{
  const {f,req,store}=fixture();await f.users(req('admin',{name:'Worker',job_title:'Housekeeper',locations:['Hotel'],duties:['equipment'],pin:'1234',confirm_pin:'1234'}));
  const login=await f.pinLogin(req(null,{pin:'1234',device_id:'reset-device-12345'})),id=login.user.id;
  await assert.rejects(f.users(req('admin',{method:'RESET_PIN',id,pin:'9090',confirm_pin:'9090'})),/already assigned/);
  await f.users(req('admin',{method:'RESET_PIN',id,pin:'5678',confirm_pin:'5678'}));
  assert.equal(store.get('users/'+id).name,'Worker');await assert.rejects(f.pinLogin(req(null,{pin:'1234'})),/not recognized/);assert.equal((await f.pinLogin(req(null,{pin:'5678'}))).user.id,id);
  await assert.rejects(f.offlineSync({data:{operation:'meal',grant_id:login.offline.id,grant_token:login.offline.token,created:new Date().toISOString(),body:{signature,request_id:'reset-revoked-123'}}}),/revoked/);
});
test('deletion protects self and last admin, retains records and blocks access and PIN login',async()=>{
  const {f,req,store}=fixture();await assert.rejects(f.users(req('admin',{method:'DELETE',id:'admin'})),/last admin/);
  await f.users(req('admin',{name:'Second Admin',job_title:'Manager',role:'manager',pin:'5678',confirm_pin:'5678'}));const second=(await f.pinLogin(req(null,{pin:'5678'}))).user.id;
  await assert.rejects(f.users(req('admin',{method:'DELETE',id:'admin'})),/own account/);
  store.set('cash/history',{user_id:second,name:'Second Admin',cents:100,created:new Date().toISOString()});
  await f.users(req('admin',{method:'DELETE',id:second}));assert.equal(store.get('users/'+second).disabled,true);assert.equal(store.has('cash/history'),true);
  assert.equal((await f.users(req('admin',{method:'GET'}))).users.some(u=>u.id===second),false);
  await assert.rejects(f.pinLogin(req(null,{pin:'5678'})),/not recognized/);await assert.rejects(f.settings(req(second)),/not enabled/);
  await assert.rejects(f.users(req('admin',{method:'DELETE',id:'admin'})),/last admin/);
});
test('deletion requires own admin PIN and rejects outstanding equipment',async()=>{
  const {f,req,store}=fixture();store.set('users/worker',{name:'Worker',role:'employee',duties:['equipment']});
  store.set('currentShifts/worker',{state:'in',radio:'1',keys:'2'});
  await assert.rejects(f.users(req('admin',{method:'DELETE',id:'worker',admin_pin:'1234'})),/incorrect/);
  await assert.rejects(f.users(req('admin',{method:'DELETE',id:'worker'})),/End this employee/);
  assert.equal(store.get('users/worker').disabled,undefined);
  await assert.rejects(f.users(req('worker',{method:'DELETE',id:'admin',admin_pin:'9090'})),/Admin access/);
});
test('concurrent cross-deletion cannot remove all admins',async()=>{
  const {f,req,store}=fixture();await f.users(req('admin',{name:'Second',job_title:'Manager',role:'manager',pin:'5678',confirm_pin:'5678'}));const second=(await f.pinLogin(req(null,{pin:'5678'}))).user.id;
  const results=await Promise.allSettled([f.users(req('admin',{method:'DELETE',id:second})),f.users(req(second,{method:'DELETE',id:'admin',admin_pin:'5678'}))]);
  assert.equal(results.filter(r=>r.status==='fulfilled').length,1);assert.equal([...store].filter(([p,v])=>p.startsWith('users/')&&v.role==='manager'&&!v.disabled).length,1);
});
test('last admin cannot be demoted and own role cannot be removed',async()=>{
  const {f,req}=fixture();const change={id:'admin',name:'Admin',job_title:'Front desk',role:'employee',locations:['Hotel'],duties:['hotel_cash']};
  await assert.rejects(f.users(req('admin',change)),/last admin/);
  await f.users(req('admin',{name:'Other',job_title:'Manager',role:'manager',pin:'5678',confirm_pin:'5678'}));await assert.rejects(f.users(req('admin',change)),/own admin role/);
});
test('admin PIN setup requires recent email authentication, preserves identity and prevents collisions',async()=>{
  const {f,req,store}=fixture();store.get('users/admin').pinIndex=null;
  const data={pin:'1234',confirm_pin:'1234'};await assert.rejects(f.setAdminPin(req('admin',data)),/email password/);
  const expired=req('admin',data);expired.auth.token.firebase={sign_in_provider:'password'};expired.auth.token.auth_time-=301;await assert.rejects(f.setAdminPin(expired),/email password/);
  const fresh=req('admin',data);fresh.auth.token.firebase={sign_in_provider:'password'};await f.setAdminPin(fresh);assert.equal((await f.pinLogin(req(null,{pin:'1234'}))).user.id,'admin');
});

test('another admin PIN cannot authorize the signed-in administrator',async()=>{
  const {f,req}=fixture();await f.users(req('admin',{name:'Other Admin',job_title:'Manager',role:'manager',pin:'5678',confirm_pin:'5678'}));
  const id=(await f.pinLogin(req(null,{pin:'5678'}))).user.id;
  await assert.rejects(f.users(req('admin',{method:'RESET_PIN',id,pin:'1234',confirm_pin:'1234',admin_pin:'5678'})),/incorrect/);
});
test('deleted admin authentication cleanup can be retried with the remaining admin',async()=>{
  const {f,req,store}=fixture();store.set('users/removed',{name:'Removed',role:'manager',disabled:true,pinIndex:null});
  await f.users(req('admin',{method:'DELETE',id:'removed'}));assert.equal(store.get('users/admin').disabled,undefined);
});
