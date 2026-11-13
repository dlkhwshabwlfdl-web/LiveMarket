# Changelog

## 0.1.0 — 2026-11-13

First release.

- Dynamic pricing engine: saturating supply/demand formula with per-trade impact cap, per-item floor/ceiling, decay back to base prices, configurable sales fee, and a daily volume guard.
- Trading via commands (`buy`, `sell`, `sellall`, `price`, `top`) and a chest GUI: category-grouped shop front and a per-item detail view with a price-history chart and 1/8/32/64 buy/sell buttons.
- Price alerts per player (`/market alert`), fired once in chat when a target is crossed; they persist across restarts.
- Admin controls: `/market reload`, `/market setprice` (logged), `/market reset`.
- All economy movement goes through Vault; sells remove items before paying; persistence in `data.yml` with asynchronous saves.
- 19 default tradable items (Farming, Wood, Mining, Mob drops), all configurable in `config.yml`; every player-facing string in `messages.yml`.
