'use strict';
const crypto = require('node:crypto');
function validatePin(value) {
  if (typeof value !== 'string' || !/^[0-9]{3,8}$/.test(value)) throw new Error('PIN must contain 3–8 digits');
  return value;
}
function pinIndex(pin, pepper) { return crypto.createHmac('sha256',pepper).update(validatePin(pin)).digest('hex'); }
function pinHash(pin,salt,pepper) { return crypto.scryptSync(pin+pepper,salt,32).toString('hex'); }
function pinMatches(pin,record,pepper) {
  const expected=Buffer.from(record.hash,'hex'), actual=Buffer.from(pinHash(pin,record.salt,pepper),'hex');
  return expected.length===actual.length && crypto.timingSafeEqual(expected,actual);
}
function cents(value) {
  const s=String(value); if(!/^\d{1,7}(\.\d{1,2})?$/.test(s)) throw new Error('Enter a positive amount with at most two decimals');
  const [whole,fraction='']=s.split('.');const n=Number(whole)*100+Number(fraction.padEnd(2,'0'));
  if(!Number.isSafeInteger(n)||n<=0||n>100000000) throw new Error('Amount must be between $0.01 and $1,000,000');
  return n;
}
function equipmentNumber(value) {const s=String(value);if(!/^\d{1,6}$/.test(s)) throw new Error('Enter the numbered radio and keys');return String(Number(s));}
function nextAction(state) {return {ready:'start',start:'lunch_out',lunch_out:'lunch_in',lunch_in:'end',end:'start'}[state];}
function validateSignature(sig) {
  if(!Array.isArray(sig)||sig.length<1||sig.length>30) throw new Error('Please draw your signature');
  let count=0,movement=false,first;
  for(const stroke of sig) {
    if(!Array.isArray(stroke)||stroke.length<2||stroke.length>2000) throw new Error('Invalid signature');
    for(const p of stroke) {
      if(!Array.isArray(p)||p.length!==2||p.some(v=>typeof v!=='number'||!Number.isFinite(v)||v<0||v>1)) throw new Error('Invalid signature');
      first ||= p;movement ||= p[0]!==first[0]||p[1]!==first[1];count++;
    }
  }
  if(Buffer.byteLength(JSON.stringify(sig),'utf8')>200000)throw new Error('Signature is too large. Clear and sign again.');
  if(count<3||!movement) throw new Error('Please draw your signature');return sig;
}
function requestId(id) {if(typeof id!=='string'||!/^[A-Za-z0-9_-]{10,100}$/.test(id)) throw new Error('Missing or invalid request ID');return id;}
function safeCsv(rows,fields) {
  const cell=v=>{let s=String(v??'');if(/^[=+\-@]/.test(s))s="'"+s;return '"'+s.replace(/"/g,'""')+'"';};
  return [fields.map(cell).join(','),...rows.map(r=>fields.map(f=>cell(r[f])).join(','))].join('\r\n');
}
function locations(u) {
  if(u.role==='manager')return ['Hotel','Restaurant'];
  return Array.isArray(u.locations)&&u.locations.length?u.locations:[u.location==='Restaurant'?'Restaurant':'Hotel'];
}
function duties(u) {
  if(u.role==='manager')return ['hotel_cash','restaurant_cash','equipment'];
  if(Array.isArray(u.duties))return u.duties;
  return /housekeep/i.test(u.job_title||'')?['equipment']:[u.location==='Restaurant'?'restaurant_cash':'hotel_cash'];
}
function cashOutlets(u){
  if(u.role==='manager')return ['Restaurant','Bar'];
  if(duties(u).includes('restaurant_cash')&&u.separate_outlets!==true)return ['Restaurant','Bar'];
  return ['Restaurant','Bar'].filter(v=>duties(u).includes(v==='Bar'?'bar_cash':'restaurant_cash'));
}
function canCash(u,department){return locations(u).includes(department)&&(department==='Hotel'?duties(u).includes('hotel_cash'):cashOutlets(u).length>0);}
function canEquipment(u){return duties(u).includes('equipment');}
function equipmentState(state){return {start:'in',lunch_in:'in',lunch_out:'out',undo_end:'out'}[state]||state;}
function transition(state,action) {
  state=equipmentState(state||'ready');
  const valid={ready:['start'],in:['out','end'],out:['in','end'],end:['start','undo_end']};
  if(!valid[state]?.includes(action))throw new Error('This shift changed. Refresh and try again.');
  return {start:'in',in:'in',out:'out',end:'end',undo_end:'out'}[action];
}
function publicUser(uid,u){
  const parts=String(u.name||'').trim().split(/\s+/);
  return {id:uid,name:u.name,first_name:u.first_name||parts[0]||'',last_name:u.last_name||parts.slice(1).join(' '),username:u.username||'',role:u.role,employment_type:u.employment_type||'full_time',job_title:u.job_title||'',location:u.location||'Hotel',locations:locations(u),duties:duties(u),cash_outlets:cashOutlets(u),separate_outlets:u.separate_outlets===true,pin_login:!!u.pinIndex};
}
module.exports={validatePin,pinIndex,pinHash,pinMatches,cents,equipmentNumber,nextAction,validateSignature,requestId,safeCsv,publicUser,locations,duties,cashOutlets,canCash,canEquipment,equipmentState,transition};
