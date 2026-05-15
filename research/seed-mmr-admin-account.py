import asyncio
import asyncpg
import uuid

DSN = "postgresql://postgres:admin@127.0.0.1:5432/trading_db"
ADMIN_ACCOUNT_ID = "76fac4b6-ea6b-44cc-a9ec-382ac3c2f4a2"
MMR_STRATEGY_DEF_ID = "a046eedf-32ee-4dd2-8072-228d68a29783"

async def main():
    conn = await asyncpg.connect(DSN)
    try:
        for interval in ["4h", "1h"]:
            existing = await conn.fetchrow(
                """
                SELECT account_strategy_id FROM account_strategy
                WHERE strategy_code = 'MMR'
                  AND account_id = $1
                  AND interval_name = $2
                  AND symbol = 'BTCUSDT'
                  AND is_deleted = false
                """,
                uuid.UUID(ADMIN_ACCOUNT_ID),
                interval
            )
            if existing:
                print(f"MMR {interval} already exists on admin account: {existing['account_strategy_id']}")
            else:
                new_id = uuid.uuid4()
                await conn.execute(
                    """
                    INSERT INTO account_strategy (
                        account_strategy_id, account_id, strategy_definition_id,
                        strategy_code, preset_name, symbol, interval_name,
                        enabled, allow_long, allow_short,
                        max_open_positions, capital_allocation_pct, priority_order,
                        is_deleted,
                        dd_kill_threshold_pct, is_kill_switch_tripped, simulated,
                        regime_gate_enabled, kelly_sizing_enabled, kelly_max_fraction,
                        visibility, version, use_risk_based_sizing, risk_pct,
                        created_time, created_by, updated_time, updated_by
                    ) VALUES (
                        $1, $2, $3,
                        'MMR', $4, 'BTCUSDT', $5,
                        false, true, true,
                        1, 5.0, 1,
                        false,
                        25.0, false, true,
                        false, false, 0.25,
                        'PRIVATE', 0, true, 0.02,
                        NOW(), 'quant-researcher', NOW(), 'quant-researcher'
                    )
                    """,
                    new_id,
                    uuid.UUID(ADMIN_ACCOUNT_ID),
                    uuid.UUID(MMR_STRATEGY_DEF_ID),
                    f"research_mmr_btc_{interval}",
                    interval
                )
                print(f"Created MMR {interval} account_strategy on admin account: {new_id}")

    finally:
        await conn.close()

asyncio.run(main())
