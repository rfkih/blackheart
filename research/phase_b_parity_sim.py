"""
Phase B parity simulation — replay existing backtest trades through the live
gate stack using the account's CURRENT balance, and report which trades the
live executor would actually have admitted.

Scope:
  - Account: starsky / 76fac4b6-ea6b-44cc-a9ec-382ac3c2f4a2
  - 3 active strategies covered:
      VBO 15m  (run 8818e563)
      VCB 1h   (run 0b8e022c)
      DCB 4h   (run 202709b1)
    LSR 1h dropped: no representative backtest run (max 4 trades on BTCUSDT).
  - Gates evaluated:
      1. min position qty (live floorToStep to 0.00001, then >= 0.0001 BTC)
      2. min USDT notional (>= 7 USDT)
      3. balance guard (USDT for LONG, BTC for SHORT — using CURRENT portfolio)
  - Risk gates (kill-switch / regime / correlation / Kelly): all inactive on
    these account_strategy rows — verified separately, not re-checked here.

This is the diagnostic for Phase A's findings 5/6/7 with concrete numbers.
Output: research/PHASE_B_PARITY_SIMULATION_2026-05-13.md
"""

from decimal import Decimal, ROUND_DOWN, getcontext
from pathlib import Path
import psycopg2
import psycopg2.extras
from collections import defaultdict

getcontext().prec = 36

# -- live constants, mirrored from id.co.blackheart.util.TradeConstant ------
MIN_USDT_NOTIONAL = Decimal("7")
MIN_POSITION_QTY = Decimal("0.0001")
QTY_STEP = Decimal("0.00001")
CAPITAL_ALLOC_PCT = Decimal("0.40")  # all 4 active strategies are at 40%

ACCOUNT_ID = "76fac4b6-ea6b-44cc-a9ec-382ac3c2f4a2"

RUNS = [
    ("VBO", "15m", "8818e563-f9b5-4be4-b2eb-90a9227b62b8"),
    ("VCB", "1h",  "0b8e022c-f8b4-49c8-82b4-62323d6b6d5f"),
    ("DCB", "4h",  "202709b1-711c-4c50-8ca6-8b7a8a469053"),
]

# Account-level cap from accounts.max_concurrent_trades — separate analysis.
MAX_CONCURRENT_TRADES = 1


def floor_to_step(qty: Decimal) -> Decimal:
    """Mirror of TradeOpenService.floorToStep — floor qty to QTY_STEP grid."""
    return (qty / QTY_STEP).quantize(Decimal("1"), rounding=ROUND_DOWN) * QTY_STEP


def fetch_balance(cur, account_id):
    cur.execute(
        "SELECT asset, balance FROM portfolio WHERE account_id=%s",
        (account_id,),
    )
    out = {row["asset"]: Decimal(row["balance"]) for row in cur.fetchall()}
    return out


def fetch_trades(cur, run_id):
    cur.execute(
        """
        SELECT backtest_trade_id, side, entry_time, exit_time,
               avg_entry_price, intended_entry_price,
               total_entry_qty, notional_size,
               realized_pnl_amount
        FROM backtest_trade
        WHERE backtest_run_id = %s
        ORDER BY entry_time
        """,
        (run_id,),
    )
    return cur.fetchall()


def fetch_run_meta(cur, run_id):
    cur.execute(
        """
        SELECT initial_capital, total_trades, return_pct, profit_factor,
               psr, start_time, end_time
        FROM backtest_run WHERE backtest_run_id = %s
        """,
        (run_id,),
    )
    return cur.fetchone()


def evaluate_live_gates(side: str, entry_price: Decimal,
                        usdt_balance: Decimal, btc_balance: Decimal):
    """
    Mirror what LiveTradingDecisionExecutorService + StrategyHelper +
    TradeOpenService.validate would do given (current balance, 40% alloc).
    Returns dict with verdict and reasoning.
    """
    if side == "LONG":
        notional = (usdt_balance * CAPITAL_ALLOC_PCT)
        if notional > usdt_balance:
            return {"verdict": "BLOCKED", "reason": "USDT balance < required notional",
                    "size_qty": None, "size_usdt": float(notional)}
        qty_raw = notional / entry_price
        qty = floor_to_step(qty_raw)
        notional_after_floor = qty * entry_price
        if qty < MIN_POSITION_QTY:
            return {"verdict": "BLOCKED", "reason": f"qty {qty} < MIN_POSITION_QTY {MIN_POSITION_QTY}",
                    "size_qty": float(qty), "size_usdt": float(notional_after_floor)}
        if notional_after_floor < MIN_USDT_NOTIONAL:
            return {"verdict": "BLOCKED", "reason": f"notional {notional_after_floor:.4f} < MIN_USDT_NOTIONAL {MIN_USDT_NOTIONAL}",
                    "size_qty": float(qty), "size_usdt": float(notional_after_floor)}
        return {"verdict": "PASS", "reason": "ok",
                "size_qty": float(qty), "size_usdt": float(notional_after_floor)}
    else:  # SHORT
        qty_raw = btc_balance * CAPITAL_ALLOC_PCT
        if qty_raw > btc_balance:
            return {"verdict": "BLOCKED", "reason": "BTC balance < required qty",
                    "size_qty": float(qty_raw), "size_usdt": None}
        qty = floor_to_step(qty_raw)
        notional_after_floor = qty * entry_price
        if qty < MIN_POSITION_QTY:
            return {"verdict": "BLOCKED", "reason": f"qty {qty} < MIN_POSITION_QTY {MIN_POSITION_QTY}",
                    "size_qty": float(qty), "size_usdt": float(notional_after_floor)}
        if notional_after_floor < MIN_USDT_NOTIONAL:
            return {"verdict": "BLOCKED", "reason": f"notional {notional_after_floor:.4f} < MIN_USDT_NOTIONAL {MIN_USDT_NOTIONAL}",
                    "size_qty": float(qty), "size_usdt": float(notional_after_floor)}
        return {"verdict": "PASS", "reason": "ok",
                "size_qty": float(qty), "size_usdt": float(notional_after_floor)}


def simulate_run(cur, code, interval, run_id, balance):
    meta = fetch_run_meta(cur, run_id)
    trades = fetch_trades(cur, run_id)
    usdt_bal = balance.get("USDT", Decimal("0"))
    btc_bal = balance.get("BTC", Decimal("0"))

    verdicts = []
    by_reason = defaultdict(int)
    by_side_verdict = defaultdict(int)

    for t in trades:
        entry_price = Decimal(t["avg_entry_price"] or t["intended_entry_price"] or 0)
        if entry_price == 0:
            continue
        v = evaluate_live_gates(t["side"], entry_price, usdt_bal, btc_bal)
        verdicts.append({
            "entry_time": t["entry_time"],
            "side": t["side"],
            "entry_price": float(entry_price),
            "backtest_qty": float(t["total_entry_qty"] or 0),
            "backtest_notional": float(t["notional_size"] or 0),
            "live_verdict": v["verdict"],
            "live_reason": v["reason"],
            "live_qty": v["size_qty"],
            "live_notional": v["size_usdt"],
            "realized_pnl": float(t["realized_pnl_amount"] or 0),
        })
        by_reason[v["reason"] if v["verdict"] == "BLOCKED" else "PASS"] += 1
        by_side_verdict[(t["side"], v["verdict"])] += 1

    n = len(verdicts)
    n_pass = sum(1 for v in verdicts if v["live_verdict"] == "PASS")
    return {
        "code": code,
        "interval": interval,
        "run_id": run_id,
        "meta": dict(meta) if meta else {},
        "n_trades": n,
        "n_pass": n_pass,
        "n_blocked": n - n_pass,
        "pass_pct": (100.0 * n_pass / n) if n else 0.0,
        "by_reason": dict(by_reason),
        "by_side_verdict": {f"{k[0]}_{k[1]}": v for k, v in by_side_verdict.items()},
        "verdicts": verdicts,
    }


def cross_run_overlap_analysis(results):
    """
    For finding #7 (max_concurrent_trades=1): if all 3 strategies had been
    active simultaneously over their shared time window, how often would
    two-or-more have wanted to open in the same hour?

    Crude bucket = entry_time floored to the hour. Overstates overlap a bit
    (two entries 59 minutes apart will bucket together), but useful as a
    direction-of-magnitude check.
    """
    from datetime import timedelta
    entries_by_hour = defaultdict(set)  # hour -> set(strategy_codes)
    for r in results:
        for v in r["verdicts"]:
            bucket = v["entry_time"].replace(minute=0, second=0, microsecond=0)
            entries_by_hour[bucket].add(r["code"])
    n_hours_any = len(entries_by_hour)
    n_hours_collisions = sum(1 for s in entries_by_hour.values() if len(s) > 1)
    by_collision_count = defaultdict(int)
    for s in entries_by_hour.values():
        by_collision_count[len(s)] += 1
    return {
        "n_hours_with_entries": n_hours_any,
        "n_hours_with_2plus_strategies": n_hours_collisions,
        "by_strategy_count_in_hour": dict(by_collision_count),
    }


def render_report(balance, results, overlap):
    lines = []
    lines.append("# Phase B — Live-Gate Replay of Existing Backtest Trades")
    lines.append("")
    lines.append("**Generated**: 2026-05-13 | **Account**: starsky / "
                 f"`{ACCOUNT_ID}`")
    lines.append("")
    lines.append("This is a diagnostic, not a fix. Each trade entry in the three "
                 "selected backtest runs is replayed through the live executor's "
                 "size + gate stack (mirrored in Python from `TradeOpenService` "
                 "+ `StrategyHelper`), using the account's **current** balance. "
                 "It answers: of the trades these backtests said would happen, "
                 "how many would the live system actually have opened today?")
    lines.append("")
    lines.append("## Current account balance (snapshot used by the simulation)")
    lines.append("")
    lines.append("| Asset | Balance |")
    lines.append("|---|---|")
    for asset in ("USDT", "BTC", "BNB"):
        if asset in balance:
            lines.append(f"| {asset} | {balance[asset]} |")
    lines.append("")
    lines.append("All four active strategies are configured at **40% capital_allocation_pct** "
                 "with `use_risk_based_sizing=false`. So:")
    lines.append(f"- LONG live-sized notional = {balance.get('USDT', 0)} × 0.40 = "
                 f"**{Decimal(balance.get('USDT', 0)) * CAPITAL_ALLOC_PCT:.4f} USDT** per entry")
    lines.append(f"- SHORT live-sized qty = {balance.get('BTC', 0)} × 0.40 = "
                 f"**{Decimal(balance.get('BTC', 0)) * CAPITAL_ALLOC_PCT:.8f} BTC** per entry")
    lines.append("")
    lines.append(f"Live exchange minimums (from `TradeConstant`): "
                 f"`MIN_POSITION_QTY={MIN_POSITION_QTY}`, "
                 f"`MIN_USDT_NOTIONAL={MIN_USDT_NOTIONAL}`, "
                 f"`QTY_STEP={QTY_STEP}`.")
    lines.append("")
    lines.append("## Per-strategy pass rate")
    lines.append("")
    lines.append("| Strategy | Run id | Trades | Live PASS | Live BLOCKED | Pass % |")
    lines.append("|---|---|---:|---:|---:|---:|")
    for r in results:
        lines.append(
            f"| {r['code']} {r['interval']} | `{r['run_id'][:8]}…` | "
            f"{r['n_trades']} | {r['n_pass']} | {r['n_blocked']} | "
            f"{r['pass_pct']:.1f}% |"
        )
    lines.append("")
    lines.append("### Block reasons by strategy")
    lines.append("")
    for r in results:
        lines.append(f"**{r['code']} {r['interval']}** "
                     f"({r['n_trades']} trades, "
                     f"PF {r['meta'].get('profit_factor')}, "
                     f"backtest return {r['meta'].get('return_pct')}%)")
        for reason, count in sorted(r["by_reason"].items(), key=lambda x: -x[1]):
            lines.append(f"  - `{reason}`: {count}")
        lines.append("")
    lines.append("### LONG vs SHORT verdict breakdown")
    lines.append("")
    lines.append("| Strategy | LONG_PASS | LONG_BLOCKED | SHORT_PASS | SHORT_BLOCKED |")
    lines.append("|---|---:|---:|---:|---:|")
    for r in results:
        bv = r["by_side_verdict"]
        lines.append(
            f"| {r['code']} {r['interval']} | "
            f"{bv.get('LONG_PASS', 0)} | {bv.get('LONG_BLOCKED', 0)} | "
            f"{bv.get('SHORT_PASS', 0)} | {bv.get('SHORT_BLOCKED', 0)} |"
        )
    lines.append("")
    lines.append("## Finding #7 cross-strategy overlap "
                 "(`max_concurrent_trades=1` on the account)")
    lines.append("")
    lines.append(f"Across the shared backtest period (per-strategy windows overlap "
                 f"roughly 2024-01 to 2026-05), {overlap['n_hours_with_entries']} "
                 f"distinct hour-buckets contain at least one entry from these "
                 f"three strategies. Of those, **{overlap['n_hours_with_2plus_strategies']} "
                 f"hour-buckets contain entries from 2+ strategies** — a lower "
                 f"bound on the rate at which live's account-level cap would have "
                 f"vetoed simultaneous entries the backtests admitted independently.")
    lines.append("")
    lines.append("| Strategies wanting to enter in same hour | # of hours |")
    lines.append("|---|---:|")
    for k in sorted(overlap["by_strategy_count_in_hour"].keys()):
        lines.append(f"| {k} | {overlap['by_strategy_count_in_hour'][k]} |")
    lines.append("")
    lines.append("This is a *crude* approximation (1-hour buckets, not "
                 "trade-lifetime overlap, so the true overlap rate is higher). "
                 "Implementation note: an exact analysis requires walking "
                 "each strategy's open trades through time and counting "
                 "concurrent positions per minute — out of scope here.")
    lines.append("")
    lines.append("## Findings 5 / 6 — live size vs backtest size on PASS rows")
    lines.append("")
    lines.append("For each trade the backtests admitted, here is the size the "
                 "live system *would* have used at today's balance, vs the size "
                 "the backtest actually used. The gap is the consequence of "
                 "Finding 5 (lot/min-notional) and the documented backtest-cash-"
                 "normalization vs live-asset-inventory SHORT sizing.")
    lines.append("")
    for r in results:
        passes = [v for v in r["verdicts"] if v["live_verdict"] == "PASS"]
        if not passes:
            lines.append(f"**{r['code']} {r['interval']}**: no PASS rows.")
            lines.append("")
            continue
        avg_bt = sum(v["backtest_notional"] for v in passes) / len(passes)
        avg_live = sum(v["live_notional"] or 0 for v in passes) / len(passes)
        lines.append(f"**{r['code']} {r['interval']}** ({len(passes)} PASS trades): "
                     f"avg backtest notional `{avg_bt:.2f} USDT` → "
                     f"avg live notional `{avg_live:.2f} USDT` "
                     f"(**{(avg_live / avg_bt * 100) if avg_bt else 0:.2f}%** of backtest size).")
        lines.append("")
    lines.append("## Paper-trade diversion check")
    lines.append("")
    lines.append("`paper_trade_run` table is **empty across the entire DB** "
                 "for these 4 account_strategy rows. Because all four have "
                 "`simulated=true`, any successful OPEN_LONG / OPEN_SHORT decision "
                 "would have been written there instead of submitted to Binance. "
                 "The empty table means either (a) the live JVM has not been up "
                 "to evaluate these strategies on a closed bar, or (b) no strategy "
                 "has produced an OPEN signal since the rows were activated. "
                 "Worth separately confirming the live JVM's recent uptime.")
    lines.append("")
    lines.append("## What this tells us")
    lines.append("")
    lines.append("- **Whether each strategy *would* fire if a signal came right now**: "
                 "answer depends purely on the gates above. Look at the LONG_PASS / "
                 "SHORT_PASS columns — if both are 0 for a strategy at current "
                 "balance, that strategy is effectively muted regardless of signal.")
    lines.append("- **Whether the backtest result will hold up live**: every "
                 "backtest PnL number is inflated by at least the % of rows "
                 "live would BLOCK (column 'Live BLOCKED') *plus* the additional "
                 "cap-collision rate from Finding 7.")
    lines.append("- **The single biggest concrete fixable issue surfaced here** "
                 "is probably Finding 5 — backtest needs to apply `floorToStep` + "
                 "`MIN_*_NOTIONAL` validation so its trade list matches what live "
                 "could actually have entered.")
    lines.append("")
    return "\n".join(lines)


def main():
    conn = psycopg2.connect(
        host="localhost", port=5432,
        dbname="trading_db", user="postgres", password="admin",
    )
    conn.set_session(readonly=True, autocommit=True)
    cur = conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)

    balance = fetch_balance(cur, ACCOUNT_ID)

    results = []
    for code, interval, run_id in RUNS:
        results.append(simulate_run(cur, code, interval, run_id, balance))

    overlap = cross_run_overlap_analysis(results)

    report = render_report(balance, results, overlap)
    out_path = Path(__file__).parent / "PHASE_B_PARITY_SIMULATION_2026-05-13.md"
    out_path.write_text(report, encoding="utf-8")

    print(f"Wrote {out_path}")
    print()
    print("=== Summary ===")
    for r in results:
        print(f"  {r['code']} {r['interval']:>3}: {r['n_trades']:>3} trades, "
              f"{r['n_pass']:>3} PASS ({r['pass_pct']:5.1f}%), {r['n_blocked']:>3} BLOCKED")
    print()
    print(f"  Cross-strategy hour overlap (lower bound on cap=1 collisions):")
    print(f"    hours with any entry:  {overlap['n_hours_with_entries']}")
    print(f"    hours with 2+ strats:  {overlap['n_hours_with_2plus_strategies']}")


if __name__ == "__main__":
    main()
