import asyncio, asyncpg

async def main():
    conn = await asyncpg.connect('postgresql://postgres:admin@127.0.0.1:5432/trading_db')
    rows2 = await conn.fetch('''
        SELECT agent_name, key, created_time, expires_at
        FROM idempotency_record
        WHERE key LIKE '%nullscreen-dcb-5m%'
        ORDER BY created_time DESC
        LIMIT 10
    ''')
    for r in rows2:
        print(dict(r))
    await conn.close()

asyncio.run(main())
