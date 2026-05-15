import asyncio
import asyncpg
import uuid

DSN = "postgresql://postgres:admin@127.0.0.1:5432/trading_db"

async def main():
    conn = await asyncpg.connect(DSN)
    try:
        rows = await conn.fetch(
            """
            SELECT account_strategy_id, strategy_code, interval_name, symbol,
                   account_id, is_deleted, enabled, visibility
            FROM account_strategy
            WHERE strategy_code = 'MMR'
            """
        )
        for r in rows:
            print(dict(r))
    finally:
        await conn.close()

asyncio.run(main())
