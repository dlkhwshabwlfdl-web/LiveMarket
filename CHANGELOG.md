# Changelog

## 0.2.0 — 2026-11-13

Modernization + a built-in analysis mode.

- All player-facing text moved to MiniMessage (Adventure): gradient titles,
  icons, thousands separators, and click-to-suggest help. No legacy colour
  codes remain; messages.yml is pure MiniMessage now.
- GUI polish: framed shop front with a balance readout, richer item lore
  (live price, trend, day change, daily volume), detail view shows the
  floor/ceiling range and a sparkline, trade buttons show estimated totals,
  and clicks/trades play sounds.
- New debug mode: `settings.debug` in config.yml or `/market debug on|off`
  (admin). Every mechanism — pricing math, trade validation, Vault results,
  decay, alerts, GUI clicks, persistence, commands — writes one structured
  `[LiveMarket-Debug]` line per event to the server log, with every number
  needed to re-derive the result.
- Verified end-to-end on Paper 1.21.11 + Vault + EssentialsX with a bot
  player running the full mechanism matrix against the debug log.


## 0.1.0 — 2026-11-13

First release.

- Dynamic pricing engine: saturating supply/demand formula with per-trade impact cap, per-item floor/ceiling, decay back to base prices, configurable sales fee, and a daily volume guard.
- Trading via commands (`buy`, `sell`, `sellall`, `price`, `top`) and a chest GUI: category-grouped shop front and a per-item detail view with a price-history chart and 1/8/32/64 buy/sell buttons.
- Price alerts per player (`/market alert`), fired once in chat when a target is crossed; they persist across restarts.
- Admin controls: `/market reload`, `/market setprice` (logged), `/market reset`.
- All economy movement goes through Vault; sells remove items before paying; persistence in `data.yml` with asynchronous saves.
- 19 default tradable items (Farming, Wood, Mining, Mob drops), all configurable in `config.yml`; every player-facing string in `messages.yml`.
