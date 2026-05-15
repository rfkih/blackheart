import sys, json
d = json.load(sys.stdin)
data = d.get('data', [])
for s in data:
    if s.get('strategyCode') == 'DCB':
        print(json.dumps(s, indent=2))
