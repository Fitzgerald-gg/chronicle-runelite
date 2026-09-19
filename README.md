# Chronicle

This plugin creates a comprehensive log of your own account's progress.

Everything is kept on your own computer, as plain JSON under `.runelite/chronicle/`, and every
figure the panel prints is worked out there from that file and the reference tables the plugin
ships with.

## Using Chronicle

Chronicle is a side panel with four tabs. The row above them sets the period that every board
reads, from the sitting you are in out to lifetime, and the search box underneath searches the
whole record at once.

### Record

<p align="left">
  <img src="docs/img/record-now.png" width="242" alt="Record: Now">
  <img src="docs/img/record-journal.png" width="242" alt="Record: Journal">
  <img src="docs/img/record-ledger.png" width="242" alt="Record: Ledger">
</p>

**Now** is the sitting you are in, under your current slayer task: xp gained, damage dealt, what
you drank and ate, what your drops were worth, and what you left on the ground. Only the cards
your play has actually earned are drawn, so the board stays as short as the session was. The
most recent drops sit along the bottom.

**Journal** is the dated feed. Levels and 99s, pets, collection log slots, quests, diaries,
combat achievements, deaths and what killed you, and the sessions themselves, filed under day
headings and filterable to one lens at a time. Above it sits a title plate for the account.

**Ledger** is the running count of everything that is neither a kill nor a skill. The purse
(alchemy, gathered, dropped, spent), distance run and walked, teleports by destination, prayers
activated, pickpockets, clue scrolls completed, and what your upkeep cost you.

### PvM

<p align="left">
  <img src="docs/img/pvm-loot.png" width="242" alt="PvM: Loot">
  <img src="docs/img/pvm-slayer.png" width="242" alt="PvM: Slayer">
</p>

**Kills** is the boss roster with your kill count against each.

**Loot** is every source and every item. Read it as received or as left behind, by source or by
kind of item, with what each is worth and what it averages per drop. Personal bests sit on the
sources that have one, and where a source paid out on slayer tasks the board can be narrowed to
just that.

**Slayer** keeps the current task on screen over three boards: the task-by-task journey with
what each one paid and how many kills gave nothing, the game's own count per monster, and the
drops the tasks produced.

**Combat** is damage dealt broken out by style, deaths, and your highest hit.

### Skilling

<p align="left">
  <img src="docs/img/skilling-skills.png" width="242" alt="Skilling: Skills">
  <img src="docs/img/skilling-drill.png" width="242" alt="Skilling: a skill opened">
</p>

**Skills** is the grid: the level and the experience each skill moved over the period, above a
summary of time played, sessions, experience and 99s reached. Opening a skill's cell drills into
that skill's own counters, such as logs chopped by tree or essence crafted by rune.

**Activities** is the same reading for minigames and the skilling bosses.

### Collection log

<p align="left">
  <img src="docs/img/collection-log.png" width="242" alt="The collection log">
</p>

The whole log under a completion figure, split across the game's own five tabs, a row per page
with the slots you hold and the kill count behind them. Opening the log in game records every
page at once rather than only the tab you clicked, and opening a page here shows the odds on the
items still missing.

### Search

<p align="left">
  <img src="docs/img/search.png" width="242" alt="Searching the record">
</p>

The box searches the record as you type: drops, collection log slots, journal lines, counters
and kinds of item. Enter opens the best match.

## Dependencies

Some functionality is gated behind two of RuneLite's built-in plugins, Slayer and Loot Tracker.
Chronicle declares both, so RuneLite enables them alongside it.

**Slayer** enables the on-task tagging of kills, so that you can see your tasks and the slayer
specific loot received.

**Loot Tracker** enables the inheritance of your loot log for searching, so a late install starts
with the drops already on your disk rather than empty.

## Licence

BSD 2-Clause, see [LICENSE](LICENSE). Not affiliated with Jagex or RuneLite.
