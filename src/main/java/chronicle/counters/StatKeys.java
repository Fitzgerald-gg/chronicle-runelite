/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle.counters;

/**
 * Journal key names that code beyond their writer reads by name. Every other counter
 * key is written as a literal at the one place that writes it.
 *
 * <p>Families share a prefix: {@code teleportsVarrock}, {@code teleportsCamelot}. The
 * panel groups them with {@code startsWith}, so a family member named the other way
 * round sits outside its group.
 *
 * <p>Renaming a key orphans everything the journal already stored under the old name.
 */
public final class StatKeys
{
	private StatKeys()
	{
	}

	// ── Combat ────────────────────────────────────────────────────────────
	// Damage totals come off hitsplats, so they count what landed.

	public static final String DAMAGE_DEALT = "damageDealt";
	public static final String HIGHEST_HIT = "highestHit";

	// Minutes filed under the activity that owned them, one key per thing:
	// timeVorkath, timeFishing, and timeIdle for the minutes nothing claimed.
	public static final String TIME_PREFIX = "time";
	public static final String TIME_IDLE = "timeIdle";

	/** The minutes key for one activity, by its name as the game gives it. */
	public static String timeKey(String name)
	{
		StringBuilder out = new StringBuilder(TIME_PREFIX);
		boolean up = true;
		for (char c : name.toCharArray())
		{
			if (c == '\'')
			{
				continue;   // K'ril is one word
			}
			if (!Character.isLetterOrDigit(c))
			{
				up = true;
				continue;
			}
			out.append(up ? Character.toUpperCase(c) : Character.toLowerCase(c));
			up = false;
		}
		return out.toString();
	}

	/** Whether a key is one of the minutes keys. */
	public static boolean isTime(String key)
	{
		return key.startsWith(TIME_PREFIX) && key.length() > TIME_PREFIX.length()
			&& Character.isUpperCase(key.charAt(TIME_PREFIX.length()));
	}
}
