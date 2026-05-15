import asyncio, asyncpg

async def main():
    conn = await asyncpg.connect('postgresql://postgres:admin@127.0.0.1:5432/trading_db')
    rows = await conn.fetch('''
        SELECT backtest_run_id, strategy_code, interval_name, status, start_time, end_time, created_at
        FROM backtest_run
        WHERE created_at > NOW() - INTERVAL '2 hours'
        ORDER BY created_at DESC
        LIMIT 20
    ''')
    for r in rows:
        print(dict(r))
    await conn.close()

asyncio.run(main())
