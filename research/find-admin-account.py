import asyncio
import asyncpg

DSN = "postgresql://postgres:admin@127.0.0.1:5432/trading_db"

async def main():
    conn = await asyncpg.connect(DSN)
    try:
        rows = await conn.fetch(
            """
            SELECT a.account_id, u.email, u.user_id, a.username
            FROM accounts a
            JOIN users u ON u.user_id = a.user_id
            WHERE u.email = 'rfkih23@gmail.com'
            """
        )
        for r in rows:
            print(dict(r))
    finally:
        await conn.close()

asyncio.run(main())
