# LiveMarket

> Dynamic pricing for survival Paper servers — prices move with supply and demand.

A Paper plugin that turns a server's shop into a living market. Instead of fixed prices, every item's price reacts to what players actually buy and sell: high demand pushes prices up, oversupply pushes them down.

![The LiveMarket shop GUI, in game](https://github.com/rconpilot/LiveMarket/releases/download/v0.2.0/gui-shop.png)

*The shop front, captured in game (Minecraft 1.21.11): items grouped by category, live price, trend and daily volume in the item lore, and a balance readout on the gold ingot.*

![An item detail view, in game](https://github.com/rconpilot/LiveMarket/releases/download/v0.2.0/gui-detail.png)

All player-facing text is modern [MiniMessage](https://docs.advntr.dev/minimessage) — gradients, icons and clickable help — and the GUI talks back with click and trade sounds, estimated totals on every trade button, and a per-item detail view with a price chart, day change and daily volume.

## How it works

- **Live prices** — each tradable item has a base price and a live price that moves with trading activity.
- **Supply & demand** — buying an item raises its price; selling lowers it. Prices drift back toward their base value over time, so the market never gets stuck.
- **A living economy** — items players actually want become valuable, and farming a single crop forever stops paying off.
- **Un-farmable by design** — price impact saturates, every item has a hard floor and ceiling, a configurable sales fee applies, and a daily volume guard dampens repeat crashes/pumps.

## Installation

1. You need a Paper 1.21.x server, Java 21, [Vault](https://www.spigotmc.org/resources/vault.34315/), and any economy plugin that provides a Vault economy (EssentialsX works).
2. Drop `LiveMarket-0.4.0.jar` into `plugins/` and start the server. If Vault or an economy provider is missing, LiveMarket logs a clear error and disables itself instead of running half-broken.
3. Edit `plugins/LiveMarket/config.yml` (items, prices, formula constants) and `messages.yml` (every player-facing string), then run `/market reload`.

## Commands & permissions

| Command | Permission | What it does |
| --- | --- | --- |
| `/market` | `livemarket.use` | Open the market GUI |
| `/market buy <item> [amount]` | `livemarket.sell` | Buy items at the current price |
| `/market sell <item> [amount]` | `livemarket.sell` | Sell items (fee applies) |
| `/market sellall` | `livemarket.sell` | Sell everything tradable in your inventory |
| `/market price [item]` | `livemarket.use` | Price of one item, or the whole list |
| `/market top` | `livemarket.use` | Biggest price movers today |
| `/market alert <item> <above\|below> <price>` | `livemarket.use` | Get a chat message when the price crosses your target (fires once, then clears) |
| `/market alert <item> remove` / `/market alerts` | `livemarket.use` | Remove / list your alerts |
| `/market pause` / `/market resume` | `livemarket.admin` | Stop / allow all trading (prices, browsing and alerts keep working while paused) |
| `/market reload` | `livemarket.admin` | Reload config and messages |
| `/market setprice <item> <price>` | `livemarket.admin` | Set an exact price (logged) |
| `/market reset <item>` | `livemarket.admin` | Return an item to its base price |
| `/market debug [on\|off]` | `livemarket.admin` | Toggle verbose mechanism logging (see below) |

The GUI mirrors the commands: the shop front groups items by category with live prices and trend arrows; clicking an item opens a detail view with a price-history chart (coloured glass panes) and buy/sell buttons for 1 / 8 / 32 / 64. A clock button in the detail view cycles the chart range — the last 9, 25 or 50 prices — so players can read both the latest moves and the longer trend.

## The pricing formula (plain English)

A trade of *n* units moves the price by a fraction of its current value:

```
move = max-move × n / (n + pressure-k)
```

- Buys move the price **up**, sells move it **down**, always by that fraction of the current price.
- The fraction **saturates**: with the defaults (`max-move` 0.30, `pressure-k` 48), selling 48 units moves the price about 15%, selling 480 moves it about 27%, and no single trade — however huge — can move it more than 30%. Bulk farming hits diminishing returns instead of printing money.
- Both constants can be overridden **per item** (`pressure-k`, `max-move` in the item's config entry), so a server can make diamonds move slowly while wheat stays jumpy — items without overrides use the global settings.
- The result is clamped to the item's **floor** and **ceiling**, always.
- When nobody trades, each price closes 1% of its gap back to its **base** price every minute (`decay-per-minute`), so a crashed market recovers on its own.
- Sales pay `price × (1 − tax-rate)`; the default fee is 5%.
- Once an item's total traded volume for the day passes `daily-volume-guard` (default 10,000 units), further price impact is multiplied by `impact-beyond-guard` (default 0.25) until midnight.

Trades execute at the price shown before the trade; the impact lands right after. Prices, history, daily volumes and alerts persist in `plugins/LiveMarket/data.yml` across restarts, and saves happen off the main thread. Saves are crash-safe: each snapshot is written to a temp file and moved into place atomically, the previous file is kept as `data.yml.bak`, and if `data.yml` is ever unreadable the plugin restores from that backup instead of wiping the market.

## Configuration

`config.yml` holds the formula constants under `settings:` and every tradable item under `items:` with its `category`, `base`, `floor` and `ceiling` (plus optional per-item `pressure-k` / `max-move`). `settings.trading-enabled: false` starts the market paused; admins can also pause and resume live with `/market pause` and `/market resume`. The defaults ship 19 items across Farming, Wood, Mining and Mob drops. `messages.yml` contains every player-facing string in MiniMessage format — no messages are hardcoded.

## Debug mode

Set `settings.debug: true` in `config.yml`, or toggle it live with `/market debug on`. Every mechanism then writes one structured, greppable line per event to the server log:

```
[LiveMarket-Debug][PRICE] item=WHEAT side=BUY amount=64 frac=0.5714 movePct=+17.14% before=2.0000 raw=2.3429 after=2.3429 clamped=false floor=0.5 ceiling=8.0 guard=false dailyVolume=64
[LiveMarket-Debug][TRADE] player=Steve item=WHEAT side=BUY amount=64 result=OK unit=2.0000 total=128.0000 balanceAfter=99872.0000
```

Categories: `PRICE` (full formula breakdown), `TRADE` (validation result and Vault totals), `DECAY`, `ALERT`, `GUI`, `STORE`, `CMD`. Every number the engine used is in the line, so any result can be re-derived from the log alone — that is how the plugin's own test suite verifies it. Leave debug off in normal operation.

## PlaceholderAPI

If [PlaceholderAPI](https://github.com/PlaceholderAPI/PlaceholderAPI) is installed, LiveMarket registers the `%livemarket_...%` expansion — use the prices anywhere placeholders work (scoreboards, chat formats, holograms, signs). Item names are case-insensitive (`wheat`, `GOLD_INGOT`); unknown items are left unparsed.

| Placeholder | Returns |
| --- | --- |
| `%livemarket_price_<item>%` | Current price (2 decimals) |
| `%livemarket_base_<item>%` | Base price |
| `%livemarket_floor_<item>%` / `%livemarket_ceiling_<item>%` | Price floor / ceiling |
| `%livemarket_change_<item>%` | Signed day change, e.g. `+17.14%` |
| `%livemarket_trend_<item>%` | `▲` rising, `▼` falling, `—` flat |
| `%livemarket_volume_<item>%` | Units traded today |
| `%livemarket_top_<n>_item%` | Biggest mover today (name), e.g. `%livemarket_top_1_item%` |
| `%livemarket_top_<n>_price%` / `%livemarket_top_<n>_change%` | That item's price / day change |
| `%livemarket_count%` | Number of tradable items |

PlaceholderAPI is an optional dependency: without it the plugin runs exactly the same, just without the placeholders.

## Building from source

Requires JDK 21 and Maven 3.9+:

```
mvn package
```

The plugin jar lands in `target/LiveMarket-0.4.0.jar`. (The project uses Maven like its sibling plugins MusicBot and ModelFramework.)

## Roadmap

- Optional MySQL persistence

## Status

**v0.4.0** — Crash-safe persistence, GUI chart ranges, per-item volatility overrides, and an admin trading pause. Verified end-to-end on Paper 1.21.11 with Vault + EssentialsX + PlaceholderAPI using the built-in debug mode and a bot player: pricing math (global and per-item), the pause switch, restart persistence, and recovery from a corrupted `data.yml` via the automatic `data.yml.bak` backup.

## License

MIT — see [LICENSE](LICENSE).
