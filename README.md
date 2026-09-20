# Chronicle

This plugin creates a comprehensive log of your own account's progress.

Everything is kept on your own computer, as plain JSON under `.runelite/chronicle/`, and every figure the panel prints is worked out there from that file and the reference tables the plugin ships with.

## Using Chronicle

Chronicle is a side panel with four tabs: Record, Hiscores, Loot and Trackers. The row above them sets the period every board reads, from the sitting you are in out to lifetime, and the search box underneath searches the whole record at once.

Every board is live. A drop, a level, a kill or a clue counts towards what is on screen the moment it happens, on whichever board you are looking at, without opening anything or switching away and back.

### Record

<p align="left">
  <img src="docs/img/record-now.png" alt="Record: Now">
  <img src="docs/img/record-journal.png" alt="Record: Journal">
  <img src="docs/img/record-ledger.png" alt="Record: Ledger">
</p>

**Now** is your current gameplay session: the xp you gained, the drops you received and what they were worth, and whatever else the session actually produced. Only what you have triggered is shown, so a quiet session will be short and it will fill out over a longer grind. Your current slayer task sits at the top when you are on one, and the most recent drops along the bottom.

**Journal** is the dated feed. Every session, level, pet, collection log slot, quest, diary entry, combat achievement, death and what killed you, filed under day headings and filterable to one lens at a time.

**Ledger** is a count of the trackers that are neither kills, skills nor combat, over two boards. Ledger & Roads is the purse, the roads, teleports and a long tail of odds and ends. Living is what you ate and drank, and what it cost.

### Hiscores

One sheet, in the order the game's own hiscores panel puts it: your skills, then the activities, then the bosses. A skill's cell shows its level and what the period moved it, and opens that skill's own counters, such as logs chopped by tree or runes crafted by type. A boss cell shows your kill count and opens a card under its own row.

The activity tiles are the way in to the rest of the account. Clues opens the tiers and what each one paid. Quests, Achievement diaries and Combat achievements open their own boards, complete with what each task asks of you, so you can look up a diary entry or a combat achievement without leaving the game. Collections opens the whole collection log.

The collection log is the one the game shows, split across the same five tabs, under your completion figure, with the slots held and the kill count where the log has given one. Each page can be clicked to show the drops inside. Opening the log in game records every page at once, not just the tab you clicked.

### Loot

**Loot** is every source and every item. Read it as received or as left behind, by source or by kind of item, with what each is worth and what it averages per drop. Personal bests sit on the sources that have one, and read by kind the whole board can be narrowed to what the slayer tasks paid.

**Slayer** keeps the current task on screen over three boards: the task-by-task journey with what each one paid and how many kills gave nothing, the game's own count per monster, and the drops the tasks produced.

### Trackers

Every counter the record keeps, in one place, filed by family. Combat is damage dealt, deaths and your highest hit. Skilling is what each skill has actually done. Offerings and the ledger hold the rest.

### Search

<p align="left">
  <img src="docs/img/search.png" alt="Searching the record">
</p>

The box searches the record as you type: drops, collection log slots, journal lines, counters, kinds of item, and the combat achievements and diary entries themselves, so you can find out what one asks for before you have done it. Enter opens what the query names, or else the board the first results came from.

## Dependencies

Some functionality is gated behind two of RuneLite's built-in plugins, Slayer and Loot Tracker. Chronicle declares both, so RuneLite loads them alongside it. If you have either switched off, Chronicle says so at the top of the panel until you turn it back on.

**Slayer** enables the on-task tagging of kills, so that you can see your tasks and the slayer specific loot received.

**Loot Tracker** is how chest, casket and every other non-NPC pickup reaches Chronicle, and its archive is inherited on first run, so a late install starts with the drops already on your disk rather than empty.

## Sync

There is a sync option under the Advanced settings. It is off by default; turned on, it sends a copy of your full journal to a server of your choice as you play. For those technically minded of you, you can use this for hosting the data on your own server and use it in whatever way you wish. One example could be a Discord bot to query the data.

## Licence

BSD 2-Clause, see [LICENSE](LICENSE). Not affiliated with Jagex or RuneLite.
