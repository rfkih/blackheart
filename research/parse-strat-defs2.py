import sys, json
d = json.load(sys.stdin)
data = d.get('data', [])
for s in data:
    sid = s.get('id')
    code = s.get('strategyCode')
    arch = s.get('archetype')
    print(f"id={sid} code={code} arch={arch}")
