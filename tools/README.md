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
