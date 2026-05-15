import asyncio
import asyncpg

DSN = "postgresql://postgres:admin@127.0.0.1:5432/trading_db"

async def main():
    conn = await asyncpg.connect(DSN)
    try:
        rows = await conn.fetch(
            """
            SELECT account_strategy_id, account_id, interval_name, is_deleted, enabled
            FROM account_strategy
            WHERE strategy_code = 'DCB' AND is_deleted = false
            """
        )
        print("Active DCB account_strategies:")
        for r in rows:
            print(dict(r))

        mro_rows = await conn.fetch(
            """
            SELECT account_strategy_id, account_id, interval_name, is_deleted
            FROM account_strategy
            WHERE strategy_code = 'MRO' AND is_deleted = false
            """
        )
        print("Active MRO account_strategies:")
        for r in mro_rows:
            print(dict(r))

    finally:
        await conn.close()

asyncio.run(main())
