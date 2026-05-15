import sys, json

data = json.load(sys.stdin)
for item in data['items']:
    title = item['title'][:70] if item['title'] else ''
    print(item['created_time'], item['entry_type'], item.get('strategy_code',''), item.get('interval_name',''), title)
