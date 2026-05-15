import asyncio, asyncpg

async def main():
    conn = await asyncpg.connect('postgresql://postgres:admin@127.0.0.1:5432/trading_db')
    rows = await conn.fetch('''
        SELECT backtest_run_id, strategy_code, interval_name, status, start_time, end_time, created_time, updated_time
        FROM backtest_run
        WHERE strategy_code = 'DCB' AND interval_name = '5m'
        AND created_time > NOW() - INTERVAL '2 hours'
        ORDER BY created_time DESC
    ''')
    for r in rows:
        print(r['created_time'], r['updated_time'], r['status'], str(r['backtest_run_id'])[:8])
    await conn.close()

asyncio.run(main())
