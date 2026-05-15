import asyncio, asyncpg

async def main():
    conn = await asyncpg.connect('postgresql://postgres:admin@127.0.0.1:5432/trading_db')
    rows = await conn.fetch('''
        SELECT backtest_run_id, strategy_code, interval_name, status, start_time, end_time, created_time
        FROM backtest_run
        WHERE created_time > NOW() - INTERVAL '2 hours'
        ORDER BY created_time DESC
        LIMIT 20
    ''')
    for r in rows:
        print(r['created_time'], r['strategy_code'], r['interval_name'], r['status'], str(r['backtest_run_id'])[:8])
    await conn.close()

asyncio.run(main())
