# Wording changes

Every on-screen string reworded in the rebuild, with what it said before. `{P}` is the
period's name (for example `22 Oct 2026` or `this session`), `{date}` a date, `{n}` a number.

## Panel

| Where | Before | After |
| --- | --- | --- |
| Any "Nothing ... in a period" note | `Nothing taken inside {P}.` (every note built this way) | `Nothing taken in {P}.` |
| Period without enough history | `Nothing closed inside {P}. A period is the distance between two baselines, and this window holds fewer than two.` | `Not enough history for {P}. A period compares two saved days, and this one has fewer than two.` |
| Recap, same case | `Nothing closed inside {P}: a period is the distance between two baselines, and this one holds fewer than two.` | `Not enough history for {P}. A period compares two saved days, and this one has fewer than two.` |
| Exact dates, bad input | `Dates read as yyyy-mm-dd (or d/m/yyyy). Nothing changed.` | `Enter dates as yyyy-mm-dd or d/m/yyyy. Nothing changed.` |
| Now, empty session | `A fresh page` | `Nothing yet` |
| Journal, empty lens | `Nothing of that kind on the record yet.` | `Nothing of this kind yet.` |
| Journal, Feats lens note | `Milestones (pets, log slots, tasks, quests, deaths) are noted here as they happen.` | `Pets, log slots, tasks, quests and deaths appear here as they happen.` |
| Journal, more entries | `Read further back` | `Show older entries` |
| Journal title card | `The journal of {name}` / `The journal` | `{name}'s journal` / `Journal` |
| Journal title card | `Kept since` | `Started` |
| Journal title card | `Days written` | `Days recorded` |
| Journal title card, tooltip | `The days, as a calendar` | `Open the calendar` |
| Journal title card, link | `what the journal holds` | `about this journal` |
| Journal title card, dry note | `still owed a {item} at {kc} {boss}` | `no {item} yet after {kc} {boss}` |
| Trackers card | `Reading` | `Period` |
| Trackers, deaths tooltip | `Who dealt them` | `What killed you` |
| Trackers, link | `every counter in one place` | `All trackers on one page` |
| Collection log, pet note | `Click pet to see odds. Skilling odds are based on current level.` | `Click a pet to see its odds. Skilling odds use your current level.` |
| Collection log, unknown pages | `Chronicle has no slot list for {page} yet, so only what you hold is known.` | `Chronicle has no slot list for {page} yet, so only the slots you hold are shown.` |
| Pet chase tooltip | `... of players have {pet} by this point. ...` | `... of players have {pet} by now. ...` |
| Pet chase tooltip | `Priced at {level}, the level you hold now, not the level each one was rolled at` | `Odds use level {level}, your current level, not the level at each roll` |
| Loot, nothing dated | `No loot has been dated yet. The roll keeps one entry a day and starts with the next drop that lands.` | `No dated loot yet. Loot is recorded by day from the next drop on.` |
| Loot, period starts before dated loot | `The dated loot roll begins {date}, which is inside {P}. Naming the part it can see as the whole period would be worse than saying nothing.` | `Loot is recorded by day from {date}, which falls in {P}, so this period cannot be shown in full.` |
| Loot, left behind card | `Kills that left one` | `Kills with loot left` |
| Loot, empty | `Drops appear here as you play: every kill, priced as it lands.` | `Drops appear here as you play, priced when they land.` |
| Loot by kind | `Dearest` | `Most valuable` |
| Loot left behind, empty | `What you walk past gets counted here, priced at the moment you declined it.` | `Loot you leave on the floor is counted here, priced when you left it.` |
| Loot left behind, empty | `Nothing walked past has been priced yet.` | `No loot left behind yet.` |
| Recap card title | `The whole record` | `Lifetime` |
| Recap card | `Dearest drop` | `Most valuable drop` |
| Recap, empty | `Nothing on the record yet.` | `Nothing recorded yet.` |
| Info page | `Anchored counts` | `Running counts` |
| Info page | `History spine` | `History file` |
| Calendar, day tooltip | `Written` | `Recorded` |
| Calendar, month line | `nothing written this month` / `{n} days written` | `nothing recorded this month` / `{n} days recorded` |
| Records | `Longest sitting` | `Longest session` |
| Records | `Longest run of days written` | `Longest streak of days played` |
| Search, info result | `what the journal holds` | `about this journal` |
| Search, item kind result | `every one you have had` | `all drops` |
| Search, hint | `enter opens the first row` | `Enter opens the first result` |
| Standing, no bosses | `Nothing on the boss sheet was killed inside {P}.` | `No bosses killed in {P}.` |
| Standing, undated kills | `Kills the journal cannot date are counted from loot instead, which reaches back only to {date} and sees a kill only where it dropped something.` | `Undated kills are counted from loot, which only goes back to {date} and only counts kills that dropped something.` |
| Clues page, empty | `No clue casket has been opened while Chronicle was watching.` | `No clue caskets recorded yet.` |
| Quests page, before login | `The quest list arrives when you next log in.` | `Quests load the next time you log in.` |
| Diaries page, before login | `Which tiers you have finished arrives when you next log in. Until then this is what each one asks for.` | `Your finished tiers load the next time you log in. Until then this shows what each tier needs.` |
| Combat achievements, new tasks | `You have also done {n} combat achievements added to the game since this copy of Chronicle was built. They are counted by the game, not named here, until the plugin updates.` | `You have also done {n} combat achievements added to the game after this version of Chronicle. They count, but are not named until the plugin updates.` |
| Combat achievements, before login | `Which tasks you have done arrives when you next log in. Until then this is what each tier asks for.` | `Your finished tasks load the next time you log in. Until then this shows what each tier needs.` |
| Standing, period tooltip title | `This sitting` | `This session` |
| Slayer, loading | `Reading the task journey from the journal…` | `Loading slayer tasks…` |
| Slayer, no task loot | `No task loot in the journal yet. It collects as tasks close.` | `No task loot yet. It is added as tasks finish.` |
| Slayer, no kill log | `No kill log yet. It copies itself the next time you open the Slayer Kill Log in game.` | `No kill log yet. It is read the next time you open the Slayer Kill Log in game.` |
| Slayer, no tasks | `No tasks in the journal yet. They collect as you play with the Slayer plugin on.` | `No tasks yet. They are recorded while the Slayer plugin is on.` |
| Slayer card title | `The journey` | `Task history` |
| Slayer, task picker tooltip | `Narrow this board to one task` | `Show one task only` |
| Task page | `Usual` | `Average` |
| Task page, no loot | `No loot recorded against this task.` | `No loot recorded for this task.` |
| Left behind, old count | `The count above is older than the itemised record. What this source leaves behind is listed here from now on.` | `The count above is from before items were tracked. Items left from this source are listed here from now on.` |
| Left behind, heading | `Declined` | `Items left` |
| Left behind, no source | `No source itemised for this yet.` | `No source recorded for this yet.` |
| Left behind, heading | `Left where` | `Where it was left` |
| Item page, tooltip | `Priced as the drop landed, or in bulk on the day the history was imported` | `Priced when it dropped, or on the day the history was imported` |
| Item page | `Days it landed` | `Days it dropped` |
| Item page, empty | `The journal hasn't seen this item drop yet.` | `No drops of this item recorded yet.` |
| Copied page note | `Loot is dated for {none / only part} of {P}.` | `Dated loot covers {none / only part} of {P}.` |
| Copied page note | `The figures above are {P}'s.` | `The figures above are for {P}.` |
| Item page | `Dropped on days the record kept whole rather than by source` | `Dropped on days recorded only as totals, not by source` |
| Item page | `No task paid this` | `No task dropped this` |
| Source page, fold | `What says so` | `Kill count sources` |
| Source page, kill count source | `Said in chat` | `Chat message` |
| Source page, link | `Open what was left on the floor` | `Show what was left on the floor` |
| Source page, empty | `The journal has no drops from this source yet.` | `No drops from this source yet.` |
| Source page, empty | `Items fill in as you play. The journal prices each drop the moment it lands.` | `Items appear as you play. Each drop is priced when it lands.` |
| Skill page heading | `WHAT IT HAS EVER PAID` | `ALL-TIME LOOT` |
| Journal feed, unnamed pet | `Pet: a new companion` | `Pet: new pet` |
| Sheet history, no counters | `The record keeps no kill counts before {date}.` / `The record keeps no counters before {date}.` | `No kill count history before {date}.` / `No tracker history before {date}.` |
| Sheet history, empty | `The record starts today: baselines close at each login, day rollover and logout, and a period is the distance between two of them.` | `History starts today. A day is saved at each login, midnight and logout, and a period compares two saved days.` |
| Sheet history, imported months | `The imported past resolves by month. Switch to Month or Year to read this era. Daily detail begins with the plugin.` | `Imported history is kept by month. Switch to Month or Year for this time. Daily detail starts when the plugin was installed.` |
| Period tooltip | `Measured since {date}, the earliest baseline on record.` | `Measured since {date}, the earliest saved day.` |
| Period tooltip | `Measured since {date}, the nearest earlier baseline.` | `Measured since {date}, the nearest earlier saved day.` |

## Recap picture

| Before | After |
| --- | --- |
| `The whole record` (title) | `Lifetime` |
| `The record keeps no experience this far back.` | `No xp history this far back.` |
| `Kill counts without a start and an end are read from loot, and count only the kills that dropped something.` | `Kill counts with no start and end are taken from loot, so they only count kills that dropped something.` |
| `The record keeps no kill counts this far back.` | `No kill count history this far back.` |
| `No boss was killed inside {P}.` | `No bosses killed in {P}.` |
| `A sitting counts the kills that dropped something.` | `A session only counts kills that dropped something.` |
| `Loot is not dated this far back, so this period's cannot be told from the rest.` | `Loot is not dated this far back, so this period's loot cannot be shown.` |
| `The record keeps no counters this far back.` | `No tracker history this far back.` |
| `Nothing closed inside {P}.` | `Nothing recorded in {P}.` |
| `Where it came from` | `Sources` |
| `Dearest` | `Most valuable` |
| `{n} more, past the height a picture can hold` (copied pages) | `{n} more did not fit` |

## Chat messages

| Before | After |
| --- | --- |
| `Chronicle: your journal followed the rename. {old} is now {new}.` | `Chronicle: moved your journal to your new name. {old} is now {new}.` |
| `Chronicle: log in first. An import lands in the logged-in account's journal.` | `Chronicle: log in first. Imports go into the logged-in account's journal.` |
| `Chronicle: adopted {n} sources · {m} loot events from your Loot Tracker.` | `Chronicle: added {n} sources · {m} loot events from your Loot Tracker.` |

## Plugin description

| Where | Before | After |
| --- | --- | --- |
| Plugin list | `A comprehensive journal of your OSRS account - loot, levels, kill counts, collection log, slayer, clues, quests, diaries and lifetime counters.` | `A journal of your OSRS account: loot, levels, kill counts, collection log, slayer, clues, quests, diaries and lifetime counters.` |
| Plugin Hub | `A comprehensive journal of your OSRS account.` | `A journal of your OSRS account.` |

## Settings

| Setting | Before | After |
| --- | --- | --- |
| Advanced (section) | `Cloud sync and how often the journal is written. Every network feature here is OFF/blank by default. Chronicle is a local journal unless you point it somewhere.` | `Cloud sync and how often the journal is saved. Nothing here uses the network until you turn it on and set a server.` |
| Enable cloud sync | `ALSO send a copy of your journal UPWARD to the server below: loot both taken and left on the ground, levels and a per-skill level/xp snapshot, kill counts and the rest of your lifetime counters, collection log, clues, quests, diaries, combat achievements, slayer tasks, pets, deaths and group-storage movements, each stamped with your display name, your account type (ironman, GIM and so on) and your RuneLite account hash. One-way: nothing is ever read back, and every feature works identically with this off. Off by default: without this, Chronicle never touches the network. Requires a server URL and a token.` | `Also send your journal to the server below: loot taken and left behind, levels and xp, kill counts and other counters, collection log, clues, quests, diaries, combat achievements, slayer tasks, pets, deaths and group storage changes, with your display name, account type and RuneLite account hash. Nothing is read back and the plugin works the same with this off. Off by default. Needs a server and a token.` |
| Enable cloud sync (warning) | `This feature submits your IP address, and your own account's activity, to the 3rd-party server you configure below: a server not controlled or verified by the RuneLite developers` | `This sends your IP address and your account's activity to the third-party server you set below. The RuneLite developers do not control or check that server.` |
| Cloud server | `Base URL of a Chronicle-compatible server to sync to (e.g. a server you run yourself). Blank by default. Cloud sync does nothing until this is set.` | `Address of a Chronicle server to sync to, such as one you run yourself. Cloud sync does nothing while this is blank.` |
| Cloud token | `The push token for this account on the configured server (issued by that server's operator). Cloud sync sends nothing without one.` | `This account's token, from whoever runs the server. Cloud sync sends nothing without one.` |
| Journal write interval | `How often the running session is folded into the on-disk journal and written out (the day's history baseline lands on the same beat). This is the most a crash or a power cut can cost you. The journal is also written at logout and when the plugin stops. With cloud sync on, the upward push rides this same cadence; with it off, the setting is purely local.` | `How often the session and the day's history are saved to disk. A crash loses at most this much. The journal is also saved at logout and when the plugin stops. Cloud sync sends on the same schedule.` |
| Import a journal | `Tick to choose a journal file to merge into THIS account's record: a backup, another computer, a copy kept for you elsewhere. Your journal is plain JSON in .runelite/chronicle/. Everything floors, so importing twice changes nothing. The tick clears itself when the file chooser opens.` | `Tick to choose a journal file, such as a backup or one from another computer, to merge into this account's journal. Journals are JSON files in .runelite/chronicle/. Importing the same file twice changes nothing. The tick clears when the file chooser opens.` |
| Push stats now | `Tick to send the journal upward immediately rather than waiting for the next flush. Does nothing unless cloud sync is on above. The tick clears itself.` | `Tick to send to the server now instead of at the next save. Needs cloud sync on. The tick clears itself.` |
