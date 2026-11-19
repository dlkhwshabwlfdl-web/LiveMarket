# Changelog

## 0.4.0 — 2026-11-19

Crash-safe persistence, chart ranges, per-item volatility, and a trading pause.

- **Crash-safe persistence.** `data.yml` is now written atomically (temp
  file, then an atomic move), the previous file is kept as
  `data.yml.bak`, and saves are sequenced so a stale async snapshot can
  never overwrite a newer one. If `data.yml` is unreadable at startup,
  the plugin restores from `data.yml.bak` with a console warning instead
  of silently resetting every price.
- **Chart ranges in the GUI.** The item detail view's clock button cycles
  the price chart through the last 9, 25, or 50 recorded points (capped
  by how much history exists); the sparkline follows the same range.
  Price decay now records a history point whenever it actually moves a
  price, so charts also show the drift between trades.
- **Per-item volatility.** Any item in `config.yml` can override the
  global pricing pressure with its own `pressure-k` and `max-move`
  values — make diamonds sluggish and wheat wild, per item.
- **Admin trading pause.** `/market pause` and `/market resume`
  (admin) freeze all buying and selling while leaving browsing and
  price checks open; `settings.trading-enabled: false` starts the
  server paused.
- Verified end-to-end on Paper 1.21.11 + Vault + EssentialsX +
  PlaceholderAPI with a bot player: global vs per-item pricing math,
  GUI range button, pause blocking buys and `sellall` while price
  queries still answer, prices surviving a restart, and a deliberately
  corrupted `data.yml` restored from `data.yml.bak` (WHEAT 5.0 and
  DIAMOND 120.24 recovered exactly).

## 0.3.0 — 2026-11-14

PlaceholderAPI support.

- New `%livemarket_...%` placeholders, registered only when PlaceholderAPI
  is present (soft dependency — the plugin runs fine without it):
  `price_<item>`, `base_<item>`, `floor_<item>`, `ceiling_<item>`,
  `change_<item>` (signed day change, e.g. `+17.14%`), `trend_<item>`
  (▲/▼/—), `volume_<item>` (units traded today), `top_<n>_item`,
  `top_<n>_price`, `top_<n>_change`, and `count`. Item names are
  case-insensitive (`%livemarket_price_wheat%`); unknown items or ranks
  are left unparsed instead of returning a made-up value.
- Verified end-to-end on Paper 1.21.11 + Vault + EssentialsX + PlaceholderAPI
  2.12.3 with a bot player: every placeholder parsed to the exact live
  value before and after a trade moved the price (WHEAT 2.00 → 2.34).

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
