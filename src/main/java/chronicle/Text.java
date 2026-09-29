/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

import java.time.Instant;
import java.util.Locale;
import static chronicle.Period.FULL_DAY;

final class Text
{
	private Text()
	{
	}

	static String fmt(long n)
	{
		return String.format(Locale.UK, "%,d", n);
	}

	static String gp(long n)
	{
		if (Math.abs(n) >= 1_000_000_000L)
		{
			return String.format(Locale.UK, "%.2fB", n / 1_000_000_000.0);
		}
		if (Math.abs(n) >= 1_000_000L)
		{
			return String.format(Locale.UK, "%.1fM", n / 1_000_000.0);
		}
		if (Math.abs(n) >= 10_000L)
		{
			return String.format(Locale.UK, "%dk", n / 1_000);
		}
		return fmt(n);
	}

	static String gps(long n)
	{
		return gp(n) + " gp";
	}

	static String named(String name, long qty)
	{
		return name + (qty > 1 ? " \u00d7" + fmt(qty) : "");
	}

	static String share(long part, long whole)
	{
		return Math.round(part * 1000.0 / whole) / 10.0 + "%";
	}

	static String climb(long from, long to)
	{
		return fmt(from) + " to " + fmt(to);
	}

	static String qtyGp(long qty, long value)
	{
		return fmt(qty) + " · " + gps(value);
	}

	static String tail(long v)
	{
		return v > 0 ? " · " + gps(v) : "";
	}

	static String xpShort(long n)
	{
		long a = Math.abs(n);
		if (a >= 100_000L)
		{
			return gp(n);
		}
		if (a >= 1_000L)
		{
			String k = String.format(Locale.UK, "%.1f", n / 1_000.0);
			return (k.endsWith(".0") ? k.substring(0, k.length() - 2) : k) + "k";
		}
		return fmt(n);
	}

	static String pb(double seconds)
	{
		long s = Math.round(seconds);
		long h = s / 3600;
		long m = (s % 3600) / 60;
		long sec = s % 60;
		return h > 0 ? String.format(Locale.UK, "%d:%02d:%02d", h, m, sec)
			: String.format(Locale.UK, "%d:%02d", m, sec);
	}

	static String count(long n, String one)
	{
		return fmt(n) + " " + plural(n, one);
	}

	static String plural(long n, String one)
	{
		return n == 1 ? one : one + "s";
	}

	static String hoursMinutes(long minutes)
	{
		return minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + "m";
	}

	static String rateText(double perHour)
	{
		return perHour >= 10 ? fmt(Math.round(perHour)) : String.format(Locale.UK, "%.1f", perHour);
	}

	static String ordinal(long n)
	{
		long last = n % 10;
		long tens = n % 100;
		String suffix = tens >= 11 && tens <= 13 ? "th"
			: last == 1 ? "st" : last == 2 ? "nd" : last == 3 ? "rd" : "th";
		return n + suffix;
	}

	static String threshold(long xp)
	{
		return xp % 1_000_000_000L == 0 ? xp / 1_000_000_000L + "B" : xp / 1_000_000L + "M";
	}

	static String dated(long ts)
	{
		return FULL_DAY.format(Instant.ofEpochMilli(ts));
	}

	static String capital(String tier)
	{
		return tier == null || tier.isEmpty() ? ""
			: Character.toUpperCase(tier.charAt(0)) + tier.substring(1);
	}
}
