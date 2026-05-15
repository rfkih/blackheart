import asyncio
import asyncpg

DSN = "postgresql://postgres:admin@127.0.0.1:5432/trading_db"

async def main():
    conn = await asyncpg.connect(DSN)
    try:
        rows = await conn.fetch(
            """
            SELECT account_strategy_id, strategy_code, account_id, interval_name,
                   is_deleted, created_time, updated_time, created_by
            FROM account_strategy
            WHERE strategy_code IN ('DCB', 'MRO', 'TPB', 'MMR')
            ORDER BY strategy_code, is_deleted, created_time DESC
            """
        )
        for r in rows:
            print(dict(r))
    finally:
        await conn.close()

asyncio.run(main())
