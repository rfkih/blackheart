import asyncio
import asyncpg
import uuid

DSN = "postgresql://postgres:admin@127.0.0.1:5432/trading_db"
ADMIN_ACCOUNT_ID = "76fac4b6-ea6b-44cc-a9ec-382ac3c2f4a2"

STRATEGIES = [
    {"code": "DCB", "def_id": "31ad9b0b-7eb2-42a5-9d95-df8c63636b32", "intervals": ["4h", "1h", "5m"]},
    {"code": "TPB", "def_id": "c63b603d-0775-4a54-9532-a4b5cc81cd5b", "intervals": ["4h", "1h"]},
    {"code": "MRO", "def_id": "c2dcedf0-4da0-4ab3-95ce-788d9a1887ff", "intervals": ["4h", "1h"]},
]

async def main():
    conn = await asyncpg.connect(DSN)
    try:
        for strat in STRATEGIES:
            for interval in strat["intervals"]:
                existing = await conn.fetchrow(
                    """
                    SELECT account_strategy_id FROM account_strategy
                    WHERE strategy_code = $1
                      AND account_id = $2
                      AND interval_name = $3
                      AND symbol = 'BTCUSDT'
                      AND is_deleted = false
                    """,
                    strat["code"],
                    uuid.UUID(ADMIN_ACCOUNT_ID),
                    interval
                )
                if existing:
                    print(f"{strat['code']} {interval} already exists: {existing['account_strategy_id']}")
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
                            $4, $5, 'BTCUSDT', $6,
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
                        uuid.UUID(strat["def_id"]),
                        strat["code"],
                        f"research_{strat['code'].lower()}_btc_{interval}",
                        interval
                    )
                    print(f"Created {strat['code']} {interval}: {new_id}")
    finally:
        await conn.close()

asyncio.run(main())
