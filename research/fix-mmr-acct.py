import asyncio
import asyncpg
import uuid

DSN = "postgresql://postgres:admin@127.0.0.1:5432/trading_db"
RESEARCH_ACCOUNT_ID = "99999999-9999-9999-9999-000000000002"

async def main():
    conn = await asyncpg.connect(DSN)
    try:
        result = await conn.execute(
            """
            UPDATE account_strategy
            SET is_deleted = true, updated_time = NOW(), updated_by = 'quant-researcher'
            WHERE strategy_code = 'MMR'
              AND account_id = $1
              AND is_deleted = false
            """,
            uuid.UUID(RESEARCH_ACCOUNT_ID)
        )
        print(f"Soft-deleted research agent MMR rows: {result}")

        remaining = await conn.fetch(
            """
            SELECT account_strategy_id, account_id, interval_name, is_deleted
            FROM account_strategy
            WHERE strategy_code = 'MMR' AND is_deleted = false
            """
        )
        print("Remaining active MMR account_strategies:")
        for r in remaining:
            print(dict(r))
    finally:
        await conn.close()

asyncio.run(main())
