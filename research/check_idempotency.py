import asyncio, asyncpg

async def main():
    conn = await asyncpg.connect('postgresql://postgres:admin@127.0.0.1:5432/trading_db')
    rows = await conn.fetch('''
        SELECT column_name
        FROM information_schema.columns
        WHERE table_name = 'idempotency_record'
        ORDER BY ordinal_position
    ''')
    print("idempotency_record columns:", [r['column_name'] for r in rows])

    rows2 = await conn.fetch('''
        SELECT *
        FROM idempotency_record
        WHERE key LIKE '%nullscreen-dcb-5m%'
        ORDER BY created_at DESC
        LIMIT 10
    ''')
    for r in rows2:
        print(dict(r))
    await conn.close()

asyncio.run(main())
