import sys, json
d = json.load(sys.stdin)
data = d.get('data', [])
for s in data:
    print(s.get('id'), s.get('strategyCode'), s.get('archetype'), s.get('presetName'), s.get('specJsonb', {}).get('anchor_ema') if s.get('specJsonb') else 'no-spec')
