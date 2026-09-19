# Generators

The bundled reference tables under `src/main/resources/chronicle/` are generated,
not hand-written. Each carries a `_meta` block saying where it came from and when.

## `gen_combat_achievements.py`

    python3 tools/gen_combat_achievements.py \
        src/main/resources/chronicle/osrs_combat_achievements.json

Reads the wiki's six rendered `Combat Achievements/<tier>` pages and keys every
task by `data-ca-task-id`, which is the game's own id and survives a task being
renamed.

Then run `CombatAchievementsTest`. It is not a formality: it holds the file
against the wiki's own tier-totals table (which the generator never reads), the
point values those tiers are worth, and the glyph table of the font the panel
paints in. A requirement shown wrong sends a player to go and do the wrong
thing, so the table is checked against things outside itself.

## `gen_achievement_diaries.py`

    python3 tools/gen_achievement_diaries.py \
        src/main/resources/chronicle/osrs_achievement_diaries.json

Reads the twelve `<region> Diary` pages. It finds the task tables by their own
`data-diary-name` / `data-diary-tier` attributes rather than by scanning forward
from a tier heading, because on Karamja the first table after the heading is the
audio player, and scanning cost that region 39 of its 44 tasks silently.

RuneLite core ships the same data as compiled per-region classes, and they carry
the task text as well as the requirements. They are not used: `DiaryRequirement`
is package private, so reading them means reflecting into another plugin's
internals, which moves without warning.

Then run `AchievementDiaryTest`.
