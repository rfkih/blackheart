import asyncio
import asyncpg

DSN = "postgresql://postgres:admin@127.0.0.1:5432/trading_db"
ADMIN_ACCOUNT_ID = "76fac4b6-ea6b-44cc-a9ec-382ac3c2f4a2"

async def main():
    conn = await asyncpg.connect(DSN)
    try:
        defs = await conn.fetch(
            """
            SELECT strategy_definition_id, strategy_code, archetype, status
            FROM strategy_definition
            WHERE is_deleted = false AND status = 'ACTIVE'
            """
        )
        print("Active strategy definitions:")
        for d in defs:
            print(dict(d))

        print("\nAll account_strategies (is_deleted=false):")
        accts = await conn.fetch(
            """
            SELECT account_strategy_id, strategy_code, account_id, interval_name, is_deleted
            FROM account_strategy
            WHERE is_deleted = false
            ORDER BY strategy_code, interval_name
            """
        )
        for a in accts:
            print(dict(a))

    finally:
        await conn.close()

asyncio.run(main())
