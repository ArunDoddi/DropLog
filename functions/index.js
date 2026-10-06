'use strict';
const {onCall,HttpsError}=require('firebase-functions/v2/https');
const logger=require('firebase-functions/logger');
const {onSchedule}=require('firebase-functions/v2/scheduler');
const {defineSecret,defineString}=require('firebase-functions/params');
const {initializeApp}=require('firebase-admin/app');
const {getFirestore,Timestamp}=require('firebase-admin/firestore');
const {getAuth}=require('firebase-admin/auth');
const {DateTime}=require('luxon');
const nodemailer=require('nodemailer');
const crypto=require('node:crypto');
const D=require('./domain');
initializeApp();const db=getFirestore();const auth=getAuth();
const PIN_PEPPER=defineSecret('PIN_PEPPER');const SMTP_PASSWORD=defineSecret('SMTP_PASSWORD');
const SMTP_HOST=defineString('SMTP_HOST');const SMTP_PORT=defineString('SMTP_PORT',{default:'587'});
const SMTP_USER=defineString('SMTP_USER');const SMTP_FROM=defineString('SMTP_FROM');
const REGION='us-central1',ZONE='America/Chicago';
const options={region:REGION,maxInstances:3,timeoutSeconds:60,memory:'256MiB'};
function monitoredCall(operation,config,handler){
  return onCall(config,async req=>{
    const started=Date.now();let outcome='success',error_category='none';
    try{return await handler(req);}catch(error){
      error_category=error instanceof HttpsError?error.code:'internal';
      outcome=['unauthenticated','permission-denied','invalid-argument','already-exists','failed-precondition','resource-exhausted'].includes(error_category)?'rejected':'error';
      throw error;
    }finally{
      // Fixed operation/category plus timing only; never include request payloads or identities.
      const fields={operation,outcome,error_category,duration_ms:Math.max(0,Date.now()-started)};
      try{if(outcome==='error')logger.error('droplog_operation',fields);else logger.info('droplog_operation',fields);}catch(_){}
    }
  });
}
function invalid(message){throw new HttpsError('invalid-argument',message);}
function checked(fn){try{return fn();}catch(e){invalid(e.message);}}
async function actor(req,manager=false){
  if(!req.auth)throw new HttpsError('unauthenticated','Please sign in');
  const authTime=Number(req.auth.token.auth_time||0);
  if(Date.now()/1000-authTime>43200)throw new HttpsError('unauthenticated','Your session expired. Sign in again.');
  const snap=await db.doc(`users/${req.auth.uid}`).get();
  if(!snap.exists||snap.data().disabled)throw new HttpsError('permission-denied','Account not enabled');
  const user=snap.data();if(manager&&user.role!=='manager')throw new HttpsError('permission-denied','Admin access required');
  return {uid:req.auth.uid,...user};
}
async function limit(req,key,maximum=5){
  const ip=req.rawRequest.ip||'unknown';const id=crypto.createHash('sha256').update(`${key}:${ip}`).digest('hex');
  const ref=db.doc(`rateLimits/${id}`);const minute=Math.floor(Date.now()/60000);
  await db.runTransaction(async t=>{const s=await t.get(ref);const v=s.data()||{};const count=v.minute===minute?v.count:0;if(count>=maximum)throw new HttpsError('resource-exhausted','Too many attempts. Wait one minute.');t.set(ref,{minute,count:count+1,expiresAt:Timestamp.fromMillis(Date.now()+3600000)});});
}
function readable(s){return s.docs.map(d=>({id:d.id,...d.data()}));}
exports.pinLogin=monitoredCall('pinLogin',{...options,secrets:[PIN_PEPPER]},async req=>{
  await limit(req,'pin');const pin=checked(()=>D.validatePin(req.data?.pin));const index=D.pinIndex(pin,PIN_PEPPER.value());
  const record=await db.doc(`pins/${index}`).get();
  if(!record.exists||!D.pinMatches(pin,record.data(),PIN_PEPPER.value()))throw new HttpsError('unauthenticated','PIN not recognized. Ask your admin.');
  const uid=record.data().uid;const snap=await db.doc(`users/${uid}`).get();
  if(!snap.exists||snap.data().disabled||!['employee','manager'].includes(snap.data().role))throw new HttpsError('permission-denied','Employee account unavailable');
  let token;
  try {token=await auth.createCustomToken(uid);} catch(error) {
    console.error('PIN custom token creation failed',error);
    throw new HttpsError('failed-precondition','PIN sign-in setup is incomplete. Ask your admin to check the pinLogin function logs and Firebase token-signing permissions.');
  }
  // An unguessable, log-only device credential. The server stores only its hash.
  const device=String(req.data?.device_id||'');
  let offline=null;
  if(/^[A-Za-z0-9_-]{16,128}$/.test(device)){
    const existing=req.data?.offline||{},old=/^[A-Za-z0-9_-]{16,128}$/.test(existing.id||'')?await db.doc(`offlineGrants/${existing.id}`).get():null;
    if(old?.exists&&old.data().uid===uid&&old.data().device===device&&old.data().pinIndex===index&&old.data().expires>Date.now()&&safeToken(existing.token,old.data().hash))offline={id:existing.id,token:existing.token,expires:old.data().expires};
    else {const id=crypto.randomUUID(),secret=crypto.randomBytes(32).toString('hex'),expires=Date.now()+30*86400000;await db.doc(`offlineGrants/${id}`).set({uid,device,pinIndex:index,hash:tokenHash(secret),issued:Date.now(),expires});offline={id,token:secret,expires};}
  }
  return {customToken:token,user:D.publicUser(uid,snap.data()),offline};
});
exports.profile=monitoredCall('profile',options,async req=>{const u=await actor(req);return {user:D.publicUser(u.uid,u)};});
async function confirmAdminPin(req,admin){
  await limit(req,`admin-pin-${admin.uid}`,5);
  if(!admin.pinIndex)throw new HttpsError('failed-precondition','Set your own admin PIN first from Admin options.');
  const pin=checked(()=>D.validatePin(req.data?.admin_pin));
  const record=await db.doc(`pins/${admin.pinIndex}`).get();
  if(!record.exists||record.data().uid!==admin.uid||!D.pinMatches(pin,record.data(),PIN_PEPPER.value()))throw new HttpsError('permission-denied','Your admin PIN is incorrect.');
}
const userGuard=()=>db.doc('settings/userManagement');
function liveAdmin(snapshot,admin){
  const value=snapshot.data();
  if(!snapshot.exists||value.disabled||value.role!=='manager'||value.pinIndex!==admin.pinIndex)throw new HttpsError('permission-denied','Your admin account changed. Sign in again.');
}
exports.users=monitoredCall('users',{...options,secrets:[PIN_PEPPER]},async req=>{
  const admin=await actor(req,true),data=req.data||{},method=data.method||'SAVE';
  if(method==='GET')return {users:readable(await db.collection('users').get()).filter(u=>!u.disabled).map(u=>D.publicUser(u.id,u)).sort((a,b)=>a.name.localeCompare(b.name))};
  const updating=typeof data.id==='string'&&data.id.length>0;
  if(updating&&!/^[A-Za-z0-9_-]{1,128}$/.test(data.id))invalid('Invalid employee ID');
  if(!['SAVE','DELETE','RESET_PIN'].includes(method))invalid('Unknown user action');
  if(method!=='SAVE'&&!updating)invalid('Choose an account');
  if(updating)await confirmAdminPin(req,admin);
  const uid=updating?data.id:`employee-${crypto.randomUUID()}`,ref=db.doc(`users/${uid}`);
  if(method==='DELETE'){
    await db.runTransaction(async t=>{
      const [guard,acting,target,admins,shift]=await Promise.all([t.get(userGuard()),t.get(db.doc(`users/${admin.uid}`)),t.get(ref),t.get(db.collection('users').where('role','==','manager')),t.get(db.doc(`currentShifts/${uid}`))]);
      liveAdmin(acting,admin);
      if(!target.exists)throw new HttpsError('not-found','Account not found');
      const old=target.data();
      if(!old.disabled&&old.role==='manager'&&admins.docs.filter(v=>!v.data().disabled).length<=1)throw new HttpsError('failed-precondition','The last admin cannot be deleted.');
      if(uid===admin.uid)throw new HttpsError('failed-precondition','You cannot delete your own account.');
      if(shift.exists&&shift.data().state!=='end')throw new HttpsError('failed-precondition','End this employee’s equipment shift before deleting their account.');
      if(old.pinIndex)t.delete(db.doc(`pins/${old.pinIndex}`));
      t.set(ref,{disabled:true,deleted:new Date().toISOString(),deleted_by:admin.uid,pinIndex:null},{merge:true});
      t.set(userGuard(),{revision:(guard.data()?.revision||0)+1});
    });
    try{await auth.deleteUser(uid);}catch(e){if(e.code!=='auth/user-not-found')throw new HttpsError('unavailable','Account access is disabled. Retry deletion to finish authentication cleanup.');}
    return {ok:true};
  }
  let pin,index,pinRef;
  if(!updating||method==='RESET_PIN'||data.pin){pin=checked(()=>D.validatePin(data.pin));if(pin!==data.confirm_pin)invalid('PIN and confirmation do not match');index=D.pinIndex(pin,PIN_PEPPER.value());pinRef=db.doc(`pins/${index}`);}
  let profile;
  if(method==='SAVE'){
    const first_name=String(data.first_name||'').trim(),last_name=String(data.last_name||'').trim();
    const name=first_name&&last_name?`${first_name} ${last_name}`:String(data.name||'').trim(),job_title=String(data.job_title||'').trim(),role=data.role||'employee';
    if(!name||name.length>100||!job_title||job_title.length>100)invalid('Enter a full name and job title (up to 100 characters)');
    if(data.first_name!==undefined&&(!first_name||!last_name))invalid('Enter both first and last name');
    if(!['employee','manager'].includes(role))invalid('Select Employee or Admin');
    const locations=role==='manager'?['Hotel','Restaurant']:(data.locations||[data.location]);
    if(!Array.isArray(locations)||!locations.length||new Set(locations).size!==locations.length||locations.some(v=>!['Hotel','Restaurant'].includes(v)))invalid('Select Hotel, Restaurant or both');
    const duties=role==='manager'?['hotel_cash','restaurant_cash','equipment']:(data.duties||D.duties({location:locations[0],job_title}));
    if(!Array.isArray(duties)||!duties.length||new Set(duties).size!==duties.length||duties.some(v=>!['hotel_cash','restaurant_cash','bar_cash','equipment'].includes(v)))invalid('Select at least one log duty');
    if(duties.includes('hotel_cash')&&!locations.includes('Hotel')||(duties.includes('restaurant_cash')||duties.includes('bar_cash'))&&!locations.includes('Restaurant'))invalid('Cash duties must match job locations');
    if(duties.includes('equipment')&&!locations.includes('Hotel'))invalid('Housekeeping requires the Hotel job location');
    if(role!=='manager'&&duties.includes('hotel_cash')&&duties.includes('equipment'))invalid('Hotel front desk and housekeeping cannot both be assigned.');
    const employment_type=data.employment_type||'full_time';if(!['ortiz','full_time'].includes(employment_type))invalid('Select Ortiz or Full time');
    profile={name,first_name:first_name||name.split(/\s+/)[0],last_name:last_name||name.split(/\s+/).slice(1).join(' '),job_title,role,location:locations.length===2?'Both':locations[0],locations,duties,employment_type};
    if(data.separate_outlets!==undefined)profile.separate_outlets=data.separate_outlets===true;
    if(!updating)await auth.createUser({uid,displayName:name});
  }
  try{await db.runTransaction(async t=>{
    const [guard,acting,target,p,admins]=await Promise.all([t.get(userGuard()),t.get(db.doc(`users/${admin.uid}`)),t.get(ref),pinRef?t.get(pinRef):Promise.resolve(null),t.get(db.collection('users').where('role','==','manager'))]);
    if(updating)liveAdmin(acting,admin);else if(!acting.exists||acting.data().disabled||acting.data().role!=='manager')throw new HttpsError('permission-denied','Admin access required');
    const old=target.data();
    if(updating&&(!target.exists||old.disabled||!['employee','manager'].includes(old.role)))throw new HttpsError('permission-denied','Account is no longer available');
    if(profile&&updating&&old.role==='manager'&&profile.role!=='manager'){
      if(admins.docs.filter(v=>!v.data().disabled).length<=1)invalid('The last admin cannot lose admin access.');
      if(uid===admin.uid)invalid('You cannot remove your own admin role');
    }
    if(p?.exists&&p.data().uid!==uid)throw new HttpsError('already-exists','That PIN is already assigned.');
    if(pin){
      const salt=crypto.randomBytes(16).toString('hex');
      if(old?.pinIndex&&old.pinIndex!==index)t.delete(db.doc(`pins/${old.pinIndex}`));
      t.set(pinRef,{uid,salt,hash:D.pinHash(pin,salt,PIN_PEPPER.value())});
    }
    const value={...(profile||{}),...(pin?{pinIndex:index}:{}),updated:new Date().toISOString(),updated_by:admin.uid};
    if(!updating){value.username=uid;value.created=new Date().toISOString();}
    t.set(ref,value,{merge:true});t.set(userGuard(),{revision:(guard.data()?.revision||0)+1});
  });}catch(e){if(!updating)await auth.deleteUser(uid).catch(()=>{});throw e;}
  return {ok:true};
});
exports.setAdminPin=monitoredCall('setAdminPin',{...options,secrets:[PIN_PEPPER]},async req=>{
  const admin=await actor(req,true),d=req.data||{};await limit(req,`admin-pin-${admin.uid}`,5);
  if(req.auth.token.firebase?.sign_in_provider!=='password'||Date.now()/1000-Number(req.auth.token.auth_time)>300)throw new HttpsError('unauthenticated','Confirm your admin email password to set your PIN.');
  const pin=checked(()=>D.validatePin(d.pin));if(pin!==d.confirm_pin)invalid('PIN and confirmation do not match');
  const index=D.pinIndex(pin,PIN_PEPPER.value()),pinRef=db.doc(`pins/${index}`),userRef=db.doc(`users/${admin.uid}`);
  await db.runTransaction(async t=>{
    const [guard,p,user]=await Promise.all([t.get(userGuard()),t.get(pinRef),t.get(userRef)]);liveAdmin(user,admin);
    if(p.exists&&p.data().uid!==admin.uid)throw new HttpsError('already-exists','That PIN is already assigned.');
    if(user.data().pinIndex&&user.data().pinIndex!==index)t.delete(db.doc(`pins/${user.data().pinIndex}`));
    const salt=crypto.randomBytes(16).toString('hex');t.set(pinRef,{uid:admin.uid,salt,hash:D.pinHash(pin,salt,PIN_PEPPER.value())});t.set(userRef,{pinIndex:index},{merge:true});t.set(userGuard(),{revision:(guard.data()?.revision||0)+1});
  });return {ok:true};
});
exports.createAdmin=monitoredCall('createAdmin',options,async req=>{
  await actor(req,true);await limit(req,'create-admin',3);
  const d=req.data||{},first=String(d.first_name||'').trim(),last=String(d.last_name||'').trim(),email=String(d.email||'').trim(),password=String(d.password||'');
  if(!first||!last||first.length>50||last.length>50||! /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)||password.length<12||password.length>128)invalid('Enter full name, valid email and a password of 12–128 characters');
  const uid=`admin-${crypto.randomUUID()}`;
  try {await auth.createUser({uid,email,password,displayName:`${first} ${last}`});}
  catch(e){throw new HttpsError(e.code==='auth/email-already-exists'?'already-exists':'failed-precondition',e.code==='auth/email-already-exists'?'That email already has an account. Assign its existing user the manager role instead.':'Could not create admin. Check Firebase Authentication setup and password policy.');}
  try {await db.runTransaction(async t=>{const guard=await t.get(userGuard());const live=await t.get(db.doc(`users/${req.auth.uid}`));if(!live.exists||live.data().disabled||live.data().role!=='manager')throw new HttpsError('permission-denied','Admin access required');t.set(db.doc(`users/${uid}`),{name:`${first} ${last}`,first_name:first,last_name:last,username:email,role:'manager',job_title:'Manager',location:'Both',locations:['Hotel','Restaurant'],duties:['hotel_cash','restaurant_cash','equipment'],created:new Date().toISOString()});t.set(userGuard(),{revision:(guard.data()?.revision||0)+1});});}
  catch(e){await auth.deleteUser(uid).catch(()=>{});throw e;}
  return {ok:true,id:uid};
});
exports.dashboard=monitoredCall('dashboard',options,async req=>{
  const u=await actor(req),start=DateTime.now().setZone(ZONE).startOf('day').toUTC().toISO();
  // Broad date filters use single-field indexes. Employee filtering happens server-side.
  const [cash,events,active,own,meals]=await Promise.all([
    db.collection('cash').where('created','>=',start).get(),db.collection('events').where('created','>=',start).get(),
    db.collection('shifts').where('state','in',['start','lunch_out','lunch_in','in','out']).get(),db.doc(`currentShifts/${u.uid}`).get(),db.collection('meals').where('created','>=',start).get()
  ]);
  const visible=rows=>rows.filter(r=>u.role==='manager'||r.user_id===u.uid);
  return {user:D.publicUser(u.uid,u),meals:visible(readable(meals)),cash:visible(readable(cash)).sort((a,b)=>b.created.localeCompare(a.created)),events:visible(readable(events)).sort((a,b)=>b.created.localeCompare(a.created)),active:visible(readable(active)),shift:own.exists?{id:own.data().shift_id,...own.data(),state:D.equipmentState(own.data().state)}:null};
});
const cashHandler=async req=>{
  const u=await actor(req),d=req.data||{};const cents=checked(()=>D.cents(d.amount)),id=checked(()=>D.requestId(d.request_id));
  if(!['Hotel','Restaurant'].includes(d.department))invalid('Choose Hotel or Restaurant');
  if(!D.canCash(u,d.department))throw new HttpsError('permission-denied','You do not have cash access at this location');
  checked(()=>D.validateSignature(d.signature));
  const venue=d.department==='Restaurant'?d.venue:'Hotel',meal=d.department==='Restaurant'?d.meal:'';
  if(d.department==='Restaurant'&&!D.cashOutlets(u).includes(venue))throw new HttpsError('permission-denied','You do not have cash access at this outlet');
  if(d.department==='Restaurant'&&(!['Restaurant','Bar'].includes(venue)||!(venue==='Bar'?['Lunch','Dinner']:['Breakfast','Lunch','Dinner']).includes(meal)))invalid('Select a valid outlet and meal');
  const signature=JSON.stringify(d.signature),profile=D.publicUser(u.uid,u);
  const ref=db.doc(`cash/${id}`);await db.runTransaction(async t=>{const [existing,live]=await Promise.all([t.get(ref),t.get(db.doc(`users/${u.uid}`))]);if(!live.exists||live.data().disabled||!D.canCash(live.data(),d.department)||(d.department==='Restaurant'&&!D.cashOutlets(live.data()).includes(venue)))throw new HttpsError('permission-denied','Cash access changed. Sign in again.');if(existing.exists){const v=existing.data();if(v.user_id!==u.uid||v.cents!==cents||v.department!==d.department||v.venue!==venue||v.meal!==meal||v.signature!==signature)throw new HttpsError('already-exists','Request ID conflict');return;}
    t.create(ref,{user_id:u.uid,name:u.name,first_name:profile.first_name,last_name:profile.last_name,department:d.department,venue,meal,cents,signature,created:eventTime(req),received:new Date().toISOString(),request_id:id});});return {ok:true};
};
exports.cash=monitoredCall('cash',options,cashHandler);
const equipmentHandler=async req=>{
  const u=await actor(req),d=req.data||{},id=checked(()=>D.requestId(d.request_id));
  if(!D.canEquipment(u))throw new HttpsError('permission-denied','You do not have equipment log access');
  checked(()=>D.validateSignature(d.signature));
  const ref=db.doc(`events/${id}`),current=db.doc(`currentShifts/${u.uid}`),signature=JSON.stringify(d.signature);
  await db.runTransaction(async t=>{
    const [old,cs,live]=await Promise.all([t.get(ref),t.get(current),t.get(db.doc(`users/${u.uid}`))]);
    if(!live.exists||live.data().disabled||!D.canEquipment(live.data()))throw new HttpsError('permission-denied','Equipment access changed. Sign in again.');
    if(old.exists){if(old.data().user_id!==u.uid||old.data().action!==d.action||old.data().signature!==signature||(d.action==='start'&&old.data().location!==d.location)||(['start','in','lunch_in'].includes(d.action)&&(old.data().radio!==checked(()=>D.equipmentNumber(d.radio))||old.data().keys!==checked(()=>D.equipmentNumber(d.keys)))))throw new HttpsError('already-exists','Request ID conflict');return;}
    const state=cs.exists?cs.data():{state:'ready'};
    const action={lunch_out:'out',lunch_in:'in'}[d.action]||d.action;
    if(req.offlineCreated&&action!=='start'&&d.expected_shift_id!==state.shift_id)throw new HttpsError('failed-precondition','Equipment shift changed on another device. Review this queued log.');
    const next=checked(()=>D.transition(state.state,action));
    const checkout=['start','in'].includes(action),wasIn=D.equipmentState(state.state)==='in';
    const location=action==='start'?d.location:(state.location||D.locations(u)[0]);
    if(action==='start'&&location!=='Hotel')invalid('Housekeeping shifts are available at Hotel only');
    if(!D.locations(u).includes(location))throw new HttpsError('permission-denied','Select an allowed equipment location');
    const radio=checkout?checked(()=>D.equipmentNumber(d.radio)):state.radio,keys=checkout?checked(()=>D.equipmentNumber(d.keys)):state.keys;
    const radioRef=db.doc(`equipmentLocks/radio-${radio}`),keyRef=db.doc(`equipmentLocks/keys-${keys}`);
    const [rLock,kLock]=await Promise.all([t.get(radioRef),t.get(keyRef)]);
    if(checkout&&(rLock.exists||kLock.exists))throw new HttpsError('failed-precondition','That radio or key set is already checked out');
    if(wasIn&&((rLock.exists&&rLock.data().user_id!==u.uid)||(kLock.exists&&kLock.data().user_id!==u.uid)))throw new HttpsError('failed-precondition','Equipment ownership changed');
    const stamp=eventTime(req),shift_id=action==='start'?id:state.shift_id;
    const value={shift_id,user_id:u.uid,name:u.name,state:next,radio,keys,location,created:action==='start'?stamp:state.created,updated:stamp};
    t.set(current,value);t.set(db.doc(`shifts/${shift_id}`),value);
    if(checkout){t.set(radioRef,{user_id:u.uid,shift_id});t.set(keyRef,{user_id:u.uid,shift_id});}
    else if(wasIn){t.delete(radioRef);t.delete(keyRef);}
    const profile=D.publicUser(u.uid,u);
    t.create(ref,{shift_id,user_id:u.uid,name:u.name,first_name:profile.first_name,last_name:profile.last_name,location,action:d.action,state:next,radio,keys,signature,created:stamp,received:new Date().toISOString(),request_id:id});
  });return {ok:true};
};
exports.equipment=monitoredCall('equipment',options,equipmentHandler);
function tokenHash(value){return crypto.createHash('sha256').update(String(value||'')).digest('hex');}
function safeToken(value,hash){return typeof value==='string'&&/^[0-9a-f]{64}$/.test(value)&&typeof hash==='string'&&crypto.timingSafeEqual(Buffer.from(tokenHash(value)),Buffer.from(hash));}
function eventTime(req){return req.offlineCreated||new Date().toISOString();}
function mealForStamp(stamp){const hour=DateTime.fromISO(stamp).setZone(ZONE).hour;return hour<12?'Breakfast':hour<15?'Lunch':'Dinner';}
function mealAccess(u){return D.locations(u).includes('Hotel')&&D.duties(u).includes('equipment');}
const mealHandler=async req=>{
  const u=await actor(req),d=req.data||{},id=checked(()=>D.requestId(d.request_id));
  if(!mealAccess(u))throw new HttpsError('permission-denied','Meals are available to Hotel housekeeping staff');
  checked(()=>D.validateSignature(d.signature));
  const stamp=eventTime(req),meal=mealForStamp(stamp),day=DateTime.fromISO(stamp).setZone(ZONE).toISODate(),ref=db.doc(`meals/${id}`),counter=db.doc(`mealDays/${u.uid}-${day}`),signature=JSON.stringify(d.signature);
  await db.runTransaction(async t=>{
    const [existing,count,server,live]=await Promise.all([t.get(ref),t.get(counter),t.get(db.doc(`mealServers/${day}-${meal}`)),t.get(db.doc(`users/${u.uid}`))]);
    if(!live.exists||live.data().disabled||!mealAccess(live.data()))throw new HttpsError('permission-denied','Meal access changed. Sign in again.');
    if(existing.exists){const old=existing.data();if(old.user_id!==u.uid||old.signature!==signature)throw new HttpsError('already-exists','Request ID conflict');return;}
    const choices=count.data()?.meals||[];
    if(choices.includes(meal))throw new HttpsError('already-exists','This meal is already logged for this day');
    if(choices.length>=2)throw new HttpsError('failed-precondition','Two free meals are already logged for this day');
    const profile=D.publicUser(u.uid,u);
    t.create(ref,{user_id:u.uid,name:u.name,first_name:profile.first_name,last_name:profile.last_name,meal,day,employment_type:u.employment_type||'full_time',server_name:server.data()?.name||'',signature,created:stamp,received:new Date().toISOString(),request_id:id});
    t.set(counter,{user_id:u.uid,day,meals:[...choices,meal]});
  });return {ok:true};
};
exports.meal=monitoredCall('meal',options,mealHandler);
exports.offlineSync=monitoredCall('offlineSync',options,async req=>{
  const d=req.data||{};
  if(!['cash','equipment','meal'].includes(d.operation)||!/^[A-Za-z0-9_-]{16,128}$/.test(d.grant_id||''))invalid('Invalid queued log');
  const grant=await db.doc(`offlineGrants/${d.grant_id}`).get();
  if(!grant.exists||!safeToken(d.grant_token,grant.data().hash))throw new HttpsError('unauthenticated','Offline access is not valid. Sign in online again and retry sync.');
  const g=grant.data(),snap=await db.doc(`users/${g.uid}`).get();
  if(!snap.exists||snap.data().disabled||snap.data().pinIndex!==g.pinIndex)throw new HttpsError('permission-denied','Offline access was revoked. Ask an admin to review this queued log.');
  const millis=Date.parse(d.created);
  if(!Number.isFinite(millis)||millis<g.issued-300000||millis>g.expires||millis>Date.now()+300000||Date.now()>g.expires+7*86400000)throw new HttpsError('failed-precondition','Offline log time or credential expiry needs admin review');
  const request={auth:{uid:g.uid,token:{auth_time:Math.floor(Date.now()/1000)}},data:d.body,offlineCreated:new Date(millis).toISOString()};
  return {cash:cashHandler,equipment:equipmentHandler,meal:mealHandler}[d.operation](request);
});
exports.settings=monitoredCall('settings',options,async req=>{
  await actor(req,true);const ref=db.doc('settings/report'),d=req.data||{};
  if(d.method!=='GET'){
    const email=String(d.email||'').trim();if(email&&!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email))invalid('Enter a valid email address');if(d.enabled&&!email)invalid('Enter a report email address');await ref.set({email,enabled:d.enabled?1:0});
  }
  const s=await ref.get();return s.exists?s.data():{email:'',enabled:0};
});
function reportBounds(first,last=first){
  if(!/^\d{4}-\d{2}-\d{2}$/.test(first||'')||!/^\d{4}-\d{2}-\d{2}$/.test(last||''))invalid('Select report dates');
  const from=DateTime.fromISO(first,{zone:ZONE}).startOf('day'),to=DateTime.fromISO(last,{zone:ZONE}).startOf('day');
  if(!from.isValid||!to.isValid||to<from||to.diff(from,'days').days>365)invalid('Choose a valid date range of at most 366 days');
  return {start:from.toUTC().toISO(),end:to.plus({days:1}).toUTC().toISO()};
}
function htmlEscape(value){return String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));}
function signatureSvg(value){
  try{const strokes=D.validateSignature(JSON.parse(value));return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 100" width="200" height="50">'+strokes.map(s=>`<polyline fill="none" stroke="#102a43" stroke-width="2" points="${s.map(p=>`${p[0]*400},${p[1]*100}`).join(' ')}"/>`).join('')+'</svg>';}catch{return 'No signature on legacy record';}
}
async function buildReport(first,last=first,type='cash'){
  const {start,end}=reportBounds(first,last),collection=type==='equipment'?'events':'cash';
  const rows=readable(await db.collection(collection).where('created','>=',start).where('created','<',end).get()).sort((a,b)=>a.created.localeCompare(b.created));
  const local=v=>DateTime.fromISO(v.created).setZone(ZONE).toFormat('yyyy-MM-dd HH:mm:ss');
  const records=rows.map(v=>({...v,amount:(v.cents/100).toFixed(2),time_central:local(v),location:v.location||v.department||'',venue:v.venue||v.department,meal:v.department==='Hotel'?'':v.meal||'Legacy / not recorded'}));
  const fields=type==='cash'?['name','first_name','last_name','department','venue','meal','amount','time_central','signature']:['name','first_name','last_name','location','action','radio','keys','time_central','signature'];
  const total=dep=>(rows.filter(v=>v.department===dep).reduce((s,v)=>s+v.cents,0)/100).toFixed(2);
  let text=`DropLog ${type} report — ${first}${last!==first?' to '+last:''} (Central time)\n`;
  if(type==='cash')text+=`Hotel: $${total('Hotel')}\nRestaurant / Bar: $${total('Restaurant')}\nCash drops: ${rows.length}\n`;
  else{
    const all=readable(await db.collection('events').where('created','<',end).get()),latest=new Map();
    for(const v of all){const prev=latest.get(v.shift_id);if(!prev||prev.created<v.created)latest.set(v.shift_id,v);}
    const outstanding=[...latest.values()].filter(v=>D.equipmentState(v.state||v.action)==='in');
    text+=`Equipment events: ${rows.length}\nOutstanding equipment at range end: ${outstanding.length}\n`+outstanding.map(v=>`${v.name}: radio ${v.radio}, keys ${v.keys}`).join('\n');
  }
  if(!rows.length)text+='No activity recorded for this period.\n';
  const display=fields.filter(f=>f!=='signature'&&f!=='first_name'&&f!=='last_name');
  const html='<!doctype html><html><meta charset="utf-8"><title>DropLog report</title><style>body{font:14px Arial;color:#102a43;padding:24px}table{border-collapse:collapse;width:100%}td,th{border:1px solid #dbe3ee;padding:8px;text-align:left}th{background:#eef3fa}svg{background:#f5f7fb}@media print{tr{break-inside:avoid}}</style><h1>'+htmlEscape(`DropLog ${type} report`)+ '</h1><pre>'+htmlEscape(text)+'</pre><table><thead><tr>'+display.map(f=>'<th>'+htmlEscape(f.replace(/_/g,' '))+'</th>').join('')+'<th>Signature</th></tr></thead><tbody>'+records.map(v=>'<tr>'+display.map(f=>'<td>'+htmlEscape(v[f])+'</td>').join('')+'<td>'+signatureSvg(v.signature)+'</td></tr>').join('')+'</tbody></table></html>';
  return {text,attachments:[{filename:type==='cash'?'cash.csv':'radio-keys.csv',content:D.safeCsv(records,fields)},{filename:`${type}-signed-report.html`,content:html,contentType:'text/html'}]};
}
async function sendMail(email,subject,text,attachments=[]){
  const transport=nodemailer.createTransport({host:SMTP_HOST.value(),port:Number(SMTP_PORT.value()),secure:Number(SMTP_PORT.value())===465,requireTLS:Number(SMTP_PORT.value())!==465,auth:{user:SMTP_USER.value(),pass:SMTP_PASSWORD.value()},connectionTimeout:15000,socketTimeout:30000});
  return transport.sendMail({from:SMTP_FROM.value(),to:email,subject,text,attachments});
}
const mailOptions={...options,secrets:[SMTP_PASSWORD]};
exports.testEmail=monitoredCall('testEmail',mailOptions,async req=>{const u=await actor(req,true);await limit(req,`mail-${u.uid}`,3);const s=await db.doc('settings/report').get();if(!s.data()?.email)invalid('Save your email address first');await sendMail(s.data().email,'DropLog test email','Your DropLog email connection is working.');return {ok:true};});
const previousDay=()=>DateTime.now().setZone(ZONE).minus({days:1}).toISODate();
exports.previousReport=monitoredCall('previousReport',{...mailOptions,timeoutSeconds:120},async req=>{const u=await actor(req,true);await limit(req,`mail-${u.uid}`,3);const s=await db.doc('settings/report').get();if(!s.data()?.email)invalid('Save your email address first');const day=previousDay(),r=await buildReport(day);await sendMail(s.data().email,`DropLog requested report — ${day}`,r.text,r.attachments);return {ok:true,date:day};});
exports.sendReport=monitoredCall('sendReport',{...mailOptions,timeoutSeconds:300},async req=>{
  const u=await actor(req,true);await limit(req,`mail-${u.uid}`,3);
  const d=req.data||{};if(!['cash','equipment'].includes(d.type))invalid('Select Cash or Equipment');
  reportBounds(d.start_date,d.end_date);
  const s=await db.doc('settings/report').get();if(!s.data()?.email)invalid('Save your email address first');
  const report=await buildReport(d.start_date,d.end_date,d.type);
  await sendMail(s.data().email,`DropLog ${d.type} report — ${d.start_date} to ${d.end_date}`,report.text,report.attachments);
  return {ok:true,start_date:d.start_date,end_date:d.end_date};
});
exports.dailyReport=onSchedule({...mailOptions,schedule:'0 4 * * *',timeZone:ZONE,timeoutSeconds:300,retryCount:3},async()=>{
  const settings=await db.doc('settings/report').get();if(!settings.data()?.enabled||!settings.data()?.email)return;
  const day=previousDay(),ref=db.doc(`deliveries/cash-${day}`);
  const acquired=await db.runTransaction(async t=>{const s=await t.get(ref);if(['sent','sending'].includes(s.data()?.status))return false;t.set(ref,{status:'sending',updated:new Date().toISOString()});return true;});
  if(!acquired)return;
  try{const r=await buildReport(day);await sendMail(settings.data().email,`DropLog daily cash report — ${day}`,r.text,r.attachments);await ref.set({status:'sent',updated:new Date().toISOString()});}
  catch(e){await ref.set({status:'failed',updated:new Date().toISOString()});throw e;}
});

exports.logServer=monitoredCall('logServer',options,async req=>{
  const u=await actor(req,true),d=req.data||{};reportBounds(d.date);
  const name=String(d.name||'').trim();
  if(!name||name.length>100||!['Breakfast','Lunch','Dinner'].includes(d.meal))invalid('Enter a server name and select a meal');
  await db.doc(`mealServers/${d.date}-${d.meal}`).set({name,day:d.date,meal:d.meal,updated_by:u.uid,updated:new Date().toISOString()});
  return {ok:true};
});
exports.activity=monitoredCall('activity',options,async req=>{
  await actor(req,true);const d=req.data||{}, {start,end}=reportBounds(d.start_date,d.end_date);
  const type=d.type||'cash';if(!['cash','equipment','meal'].includes(type))invalid('Select a log type');
  const collection={cash:'cash',equipment:'events',meal:'meals'}[type];
  const rows=readable(await db.collection(collection).where('created','>=',start).where('created','<',end).get()).sort((a,b)=>a.created.localeCompare(b.created));
  const servers=type==='meal'?readable(await db.collection('mealServers').where('day','>=',d.start_date).where('day','<=',d.end_date).get()):[];
  for(const r of rows){r.day=DateTime.fromISO(r.created).setZone(ZONE).toISODate();if(type==='meal')r.server_name=servers.find(v=>v.day===r.day&&v.meal===r.meal)?.name||r.server_name||'';}
  return {rows,servers};
});
