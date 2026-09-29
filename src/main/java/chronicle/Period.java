/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

import chronicle.HistoryLog.Baseline;
import chronicle.panel.StatRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
final class Period
{
	static final DateTimeFormatter DAY =
		DateTimeFormatter.ofPattern("d MMM", Locale.UK).withZone(ZoneId.systemDefault());
	static final DateTimeFormatter TASK_DAY =
		DateTimeFormatter.ofPattern("d MMM yy", Locale.UK).withZone(ZoneId.systemDefault());
	static final DateTimeFormatter FULL_DAY =
		DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK).withZone(ZoneId.systemDefault());
	static final DateTimeFormatter MONTH_YEAR =
		DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK).withZone(ZoneId.systemDefault());

	static final String SESSION = "Session";

	final LocalDate start;
	final LocalDate end;
	final String label;
	final boolean whole;
	final boolean session;
	final long sessionStart;

	@RequiredArgsConstructor
	static final class Span
	{
		final Baseline opening;
		final Baseline earliest;
		final Baseline closing;
	}

	static Period of(String granularity, LocalDate cursor, LocalDate from, LocalDate to,
		long sessionStart, TreeMap<LocalDate, Baseline> spine)
	{
		LocalDate end = cursor;
		LocalDate start;
		String label;
		if (from != null && to != null)
		{
			start = from;
			end = to.isAfter(LocalDate.now()) ? LocalDate.now() : to;
			label = start.format(TASK_DAY) + " - " + end.format(TASK_DAY);
		}
		else
		{
			switch (granularity)
			{
				case SESSION:
					start = sessionStart > 0 ? dayOf(sessionStart) : LocalDate.now();
					end = LocalDate.now();
					label = "This session";
					break;
				case "Lifetime":
					start = spine == null || spine.isEmpty() ? end.minusYears(30) : spine.firstKey();
					end = LocalDate.now();
					label = "Lifetime";
					break;
				case "Day":
					start = end;
					label = end.format(FULL_DAY);
					break;
				case "Month":
					start = end.withDayOfMonth(1);
					end = start.plusMonths(1).minusDays(1);
					label = start.format(MONTH_YEAR);
					break;
				case "Year":
					start = end.withDayOfYear(1);
					end = start.plusYears(1).minusDays(1);
					label = String.valueOf(start.getYear());
					break;
				case "Week":
				default:
					start = end.minusDays(6);
					label = start.format(DAY) + " - " + end.format(FULL_DAY);
					break;
			}
		}
		return new Period(start, end, label, "Lifetime".equals(granularity) && from == null,
			SESSION.equals(granularity) && from == null, sessionStart);
	}

	long[] ms()
	{
		if (whole)
		{
			return new long[]{Long.MIN_VALUE / 2, Long.MAX_VALUE / 2};
		}
		if (session)
		{
			return new long[]{sessionStart > 0 ? sessionStart : startMs(LocalDate.now()),
				System.currentTimeMillis()};
		}
		return new long[]{startMs(start), startMs(end.plusDays(1)) - 1};
	}

	boolean inside(long ts)
	{
		if (whole)
		{
			return true;
		}
		if (ts <= 0)
		{
			return !session;
		}
		long[] ms = ms();
		return ts >= ms[0] && ts <= ms[1];
	}

	boolean reachesToday()
	{
		return !end.isBefore(LocalDate.now());
	}

	String inSentence()
	{
		return label.startsWith("This ")
			? Character.toLowerCase(label.charAt(0)) + label.substring(1) : label;
	}

	boolean closesOnTheClient(Map.Entry<LocalDate, Baseline> from)
	{
		if (from == null || end.isBefore(LocalDate.now()))
		{
			return false;
		}
		return session || !from.getKey().isBefore(start.minusDays(1));
	}

	Span span(TreeMap<LocalDate, Baseline> spine)
	{
		Map.Entry<LocalDate, Baseline> from = HistoryLog.windowStart(spine, start, end);
		Map.Entry<LocalDate, Baseline> at = spine.floorEntry(end);
		if (at == null || from == null
			|| (at.getKey().equals(from.getKey()) && !closesOnTheClient(from)))
		{
			return null;
		}
		return new Span(HistoryLog.stateAt(spine, from.getKey()),
			HistoryLog.earliest(spine, at.getKey()),
			HistoryLog.stateAt(spine, at.getKey()));
	}

	Map<String, Long> closingNow(Map<String, Long> closing, Map<String, Long> live)
	{
		if (closing == null || live == null || live.isEmpty() || !reachesToday())
		{
			return closing;
		}
		Map<String, Long> out = new HashMap<>(closing);
		for (Map.Entry<String, Long> e : live.entrySet())
		{
			if (e.getValue() != null)
			{
				out.merge(e.getKey(), e.getValue(), Math::max);
			}
		}
		return out;
	}

	static Map<String, Long> peaksNotDeltas(Map<String, Long> moved, Span s)
	{
		for (String key : StatRegistry.peakKeys())
		{
			if (!moved.containsKey(key))
			{
				continue;
			}
			long opened = s.opening.counters == null ? 0
				: s.opening.counters.getOrDefault(key, 0L);
			long shut = s.closing.counters == null ? 0
				: s.closing.counters.getOrDefault(key, 0L);
			if (shut > opened)
			{
				moved.put(key, shut);
			}
			else
			{
				moved.remove(key);
			}
		}
		return moved;
	}

	static long noon(LocalDate d)
	{
		return d.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
	}

	static LocalDate dayOf(long ms)
	{
		return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate();
	}

	static long startMs(LocalDate d)
	{
		return d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
	}
}
