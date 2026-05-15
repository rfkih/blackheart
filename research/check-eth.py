import asyncio
import asyncpg

DSN = "postgresql://postgres:admin@127.0.0.1:5432/trading_db"

async def main():
    conn = await asyncpg.connect(DSN)
    try:
        count = await conn.fetchval(
            "SELECT COUNT(*) FROM feature_store WHERE symbol = 'ETHUSDT'"
        )
        print(f"ETH feature_store rows: {count}")

        if count > 0:
            row = await conn.fetchrow(
                "SELECT MIN(timestamp_utc), MAX(timestamp_utc) FROM feature_store WHERE symbol = 'ETHUSDT'"
            )
            print(f"ETH date range: {row[0]} to {row[1]}")

        eth_md = await conn.fetchval(
            "SELECT COUNT(*) FROM market_data WHERE symbol = 'ETHUSDT'"
        )
        print(f"ETH market_data rows: {eth_md}")

    finally:
        await conn.close()

asyncio.run(main())
