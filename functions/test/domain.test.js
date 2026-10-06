const {test}=require('node:test');const assert=require('node:assert/strict');const D=require('../domain');
test('cash stored in exact cents; bad and fractional inputs rejected',()=>{
  assert.equal(D.cents('123.45'),12345);assert.equal(D.cents('0.01'),1);assert.equal(D.cents('1000000'),100000000);
  for(const v of ['0','-1','NaN','1.001','1e2','1000000.01','',null])assert.throws(()=>D.cents(v));
});
test('PIN digit bounds and leading zeros preserved',()=>{
  assert.equal(D.validatePin('001'), '001');assert.equal(D.validatePin('12345678'),'12345678');
  for(const v of ['12','123456789','12a','１２３',123])assert.throws(()=>D.validatePin(v));
});
test('PIN HMAC indexes deterministic and keyed; salted hash verifies only the right PIN',()=>{
  assert.equal(D.pinIndex('0012','secret'),D.pinIndex('0012','secret'));assert.notEqual(D.pinIndex('0012','secret'),D.pinIndex('0012','other'));
  const record={salt:'salt-a',hash:D.pinHash('0012','salt-a','pepper')};assert.equal(D.pinMatches('0012',record,'pepper'),true);assert.equal(D.pinMatches('0013',record,'pepper'),false);
  assert.notEqual(record.hash,D.pinHash('0012','salt-b','pepper'));
});
test('equipment numbers normalized and cannot escape document IDs',()=>{
  assert.equal(D.equipmentNumber('0007'),'7');assert.throws(()=>D.equipmentNumber('../7'));assert.throws(()=>D.equipmentNumber('1234567'));
});
test('regular four-event shift order',()=>{
  assert.equal(D.nextAction('ready'),'start');assert.equal(D.nextAction('start'),'lunch_out');assert.equal(D.nextAction('lunch_out'),'lunch_in');assert.equal(D.nextAction('lunch_in'),'end');assert.equal(D.nextAction('end'),'start');
});
test('drawn signature validation and size limits',()=>{
  const sig=[[[.1,.1],[.2,.2],[.3,.2]]];assert.deepEqual(D.validateSignature(sig),sig);
  for(const v of [[],[[[.1,.1]]],[[[0,0],[0,0],[0,0]]],[[[0,0],[2,0],[.1,.2]]],[[[0,0],[NaN,0],[.1,.2]]]])assert.throws(()=>D.validateSignature(v));
});
test('CSV escapes quotes, commas, formula prefixes and multiline signatures',()=>{
  const result=D.safeCsv([{name:'=SUM(1,2)',signature:'"hello"\nworld'}],['name','signature']);assert.match(result,/"'=SUM\(1,2\)"/);assert.match(result,/""hello""/);
});
test('public profiles never expose credential material',()=>{
  const u=D.publicUser('id',{name:'Rosa',role:'employee',pin:'1234',pinIndex:'secret',hash:'secret',job_title:'Housekeeper',location:'Hotel'});assert.equal(u.name,'Rosa');assert.equal('pin' in u,false);assert.equal('hash' in u,false);assert.equal('pinIndex' in u,false);
});
test('idempotency request IDs exclude slashes and very short values',()=>{assert.equal(D.requestId('abcd-123456'),'abcd-123456');assert.throws(()=>D.requestId('short'));assert.throws(()=>D.requestId('abcd/123456'));});

test('flexible equipment transitions and legacy shift state mapping',()=>{
  assert.equal(D.transition('ready','start'),'in');assert.equal(D.transition('start','out'),'out');assert.equal(D.transition('out','in'),'in');assert.equal(D.transition('in','end'),'end');assert.equal(D.transition('out','end'),'end');assert.equal(D.transition('end','undo_end'),'out');assert.throws(()=>D.transition('in','in'));assert.throws(()=>D.transition('ready','undo_end'));
});
test('permissions separate locations from duties and managers have all access',()=>{
  const u={locations:['Hotel','Restaurant'],duties:['equipment','restaurant_cash'],role:'employee'};assert.equal(D.canCash(u,'Hotel'),false);assert.equal(D.canCash(u,'Restaurant'),true);assert.equal(D.canEquipment(u),true);assert.equal(D.canCash({role:'manager'},'Hotel'),true);
});
