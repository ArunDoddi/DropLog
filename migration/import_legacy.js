// Run from project root after functions/npm install and gcloud auth application-default login.
// Dry run unless --commit; never overwrites an existing Firestore log.
const {initializeApp,applicationDefault}=require('../functions/node_modules/firebase-admin/app');
const {getFirestore}=require('../functions/node_modules/firebase-admin/firestore');
const {getAuth}=require('../functions/node_modules/firebase-admin/auth');
const fs=require('node:fs');
const [file,projectId,managerUid,...flags]=process.argv.slice(2);
if(!file||!projectId||!managerUid)throw new Error('Usage: node migration/import_legacy.js export.json PROJECT_ID ADMIN_UID [--commit]');
const data=JSON.parse(fs.readFileSync(file,'utf8'));const users=new Map((data.users||[]).map(u=>[String(u.id),u]));
const uid=id=>users.get(String(id))?.role==='manager'?managerUid:`legacy-${id}`;
console.log('Import totals:',Object.fromEntries(Object.entries(data).map(([k,v])=>[k,v.length])));
if(!flags.includes('--commit')){console.log('Dry run only. Add --commit to import.');process.exit(0);}
initializeApp({credential:applicationDefault(),projectId});const db=getFirestore(),auth=getAuth();
async function create(path,value){try{await db.doc(path).create(value);}catch(e){if(e.code!==6)throw e;}}
(async()=>{
  const admin=await db.doc(`users/${managerUid}`).get();if(!admin.exists||admin.data().role!=='manager')throw new Error('Create the cloud admin profile first');
  for(const u of data.users||[]){if(u.role==='manager')continue;const id=uid(u.id);try{await auth.createUser({uid:id,displayName:u.name});}catch(e){if(e.code!=='auth/uid-already-exists')throw e;}
    await create(`users/${id}`,{name:u.name,username:id,role:'employee',job_title:u.job_title||'Employee',location:u.location||'Hotel',created:new Date().toISOString()});}
  for(const r of data.cash||[]){const id=`legacy-cash-${r.id}`;await create(`cash/${id}`,{...r,user_id:uid(r.user_id),name:users.get(String(r.user_id))?.name||'Legacy employee',created:new Date(r.created).toISOString(),id,request_id:id});}
  for(const r of data.events||[]){const id=`legacy-event-${r.id}`;await create(`events/${id}`,{...r,shift_id:`legacy-shift-${r.shift_id}`,user_id:uid(r.user_id),name:users.get(String(r.user_id))?.name||'Legacy employee',created:new Date(r.created).toISOString(),id,request_id:id});}
  for(const r of data.shifts||[]){const id=`legacy-shift-${r.id}`,user_id=uid(r.user_id);const value={...r,shift_id:id,user_id,name:users.get(String(r.user_id))?.name||'Legacy employee',created:new Date(r.created).toISOString()};await create(`shifts/${id}`,value);
    if(r.state!=='end'){
      await db.runTransaction(async t=>{const current=db.doc(`currentShifts/${user_id}`);const radio=db.doc(`equipmentLocks/radio-${r.radio}`),keys=db.doc(`equipmentLocks/keys-${r.keys}`);
        const [cs,rs,ks]=await Promise.all([t.get(current),t.get(radio),t.get(keys)]);
        if(cs.exists&&cs.data().shift_id!==id)throw new Error('Existing active cloud shift conflicts with import');
        if(['start','lunch_in'].includes(r.state)&&((rs.exists&&rs.data().shift_id!==id)||(ks.exists&&ks.data().shift_id!==id)))throw new Error('Existing equipment lock conflicts with import');
        t.set(current,value);if(['start','lunch_in'].includes(r.state)){t.set(radio,{user_id,shift_id:id});t.set(keys,{user_id,shift_id:id});}
      });
    }
  }
  if(data.settings?.[0])await create('settings/report',{email:data.settings[0].email||'',enabled:0});
  console.log('Imported. Assign employee PINs in Admin > Users. Reports start disabled; test email before enabling.');
})().catch(e=>{console.error(e.message);process.exitCode=1;});
