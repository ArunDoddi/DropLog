"""One-time read-only export, not a running server. Python standard library only."""
import argparse,sqlite3,json
p=argparse.ArgumentParser();p.add_argument('database');p.add_argument('output');a=p.parse_args()
c=sqlite3.connect('file:'+a.database+'?mode=ro',uri=True);c.row_factory=sqlite3.Row
result={}
for table in ['users','cash','events','shifts','settings']:
    rows=[dict(r) for r in c.execute('SELECT * FROM '+table)]
    if table=='users':
        rows=[{k:v for k,v in r.items() if k in ['id','name','username','role','job_title','location']} for r in rows]
    result[table]=rows
with open(a.output,'w') as f:json.dump(result,f,indent=2)
print('Exported users and logs. No password hashes, PINs, secrets or sessions exported.')
