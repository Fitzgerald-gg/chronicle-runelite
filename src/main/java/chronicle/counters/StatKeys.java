/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle.counters;

public final class StatKeys
{
	private StatKeys()
	{
	}

	public static final String DAMAGE_DEALT = "damageDealt";
	public static final String HIGHEST_HIT = "highestHit";

	public static final String TIME_PREFIX = "time";
	public static final String TIME_IDLE = "timeIdle";

	public static String timeKey(String name)
	{
		StringBuilder out = new StringBuilder(TIME_PREFIX);
		boolean up = true;
		for (char c : name.toCharArray())
		{
			if (c == '\'')
			{
				continue;
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

	public static boolean isTime(String key)
	{
		return key.startsWith(TIME_PREFIX) && key.length() > TIME_PREFIX.length()
			&& Character.isUpperCase(key.charAt(TIME_PREFIX.length()));
	}
}
