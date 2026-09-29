/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import static chronicle.Ui.DAY;
import static chronicle.Ui.FULL_DAY;
import static chronicle.Ui.MONTH_YEAR;
import static chronicle.Ui.TASK_DAY;
import static chronicle.Ui.dayOf;

final class Period
{
	static final String LIFETIME = "Lifetime";
	static final String SESSION = "Session";
	static final String[] NAMES = {LIFETIME, "Year", "Month", "Week", "Day", SESSION};

	String granularity = LIFETIME;
	LocalDate cursor = LocalDate.now();
	LocalDate from;
	LocalDate to;
	private LocalDate shownStart;
	private LocalDate shownEnd;

	@RequiredArgsConstructor
	static final class Window
	{
		final LocalDate start;
		final LocalDate end;
		final String label;
	}

	boolean whole()
	{
		return LIFETIME.equals(granularity) && from == null;
	}

	boolean session()
	{
		return SESSION.equals(granularity) && from == null;
	}

	boolean exact()
	{
		return from != null && to != null;
	}

	boolean steps()
	{
		return exact() || !LIFETIME.equals(granularity) && !session();
	}

	void choose(String g, LocalDate keepEnd)
	{
		LocalDate today = LocalDate.now();
		granularity = g;
		from = null;
		to = null;
		cursor = keepEnd.isAfter(today) ? today : keepEnd;
	}

	void day(long ts)
	{
		granularity = "Day";
		from = null;
		to = null;
		cursor = dayOf(ts);
	}

	void exact(LocalDate f, LocalDate t)
	{
		from = t.isBefore(f) ? t : f;
		to = t.isBefore(f) ? f : t;
	}

	LocalDate suggestedFrom()
	{
		return shownStart != null ? shownStart : LocalDate.now().minusDays(6);
	}

	LocalDate suggestedTo()
	{
		return shownEnd != null ? shownEnd : LocalDate.now();
	}

	boolean canStepForward()
	{
		return exact() ? to.isBefore(LocalDate.now()) : !step(cursor, 1).isAfter(LocalDate.now());
	}

	void step(int by)
	{
		if (exact())
		{
			long span = ChronoUnit.DAYS.between(from, to) + 1;
			from = from.plusDays(by * span);
			to = to.plusDays(by * span);
			return;
		}
		LocalDate next = step(cursor, by);
		cursor = next.isAfter(LocalDate.now()) ? LocalDate.now() : next;
	}

	private LocalDate step(LocalDate d, int by)
	{
		switch (granularity)
		{
			case "Day":
				return d.plusDays(by);
			case "Month":
				return d.withDayOfMonth(1).plusMonths(by + 1).minusDays(1);
			case "Year":
				return d.withDayOfYear(1).plusYears(by + 1).minusDays(1);
			default:
				return d.plusDays(7L * by);
		}
	}

	Window window(long sessionStart, LocalDate firstDay)
	{
		LocalDate today = LocalDate.now();
		LocalDate start;
		LocalDate end = cursor;
		String label;
		if (exact())
		{
			start = from;
			end = to.isAfter(today) ? today : to;
			label = start.format(TASK_DAY) + " - " + end.format(TASK_DAY);
		}
		else
		{
			switch (granularity)
			{
				case SESSION:
					start = sessionStart > 0 ? dayOf(sessionStart) : today;
					end = today;
					label = "This session";
					break;
				case LIFETIME:
					start = firstDay == null ? end.minusYears(30) : firstDay;
					end = today;
					label = LIFETIME;
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
				default:
					start = end.minusDays(6);
					label = start.format(DAY) + " - " + end.format(FULL_DAY);
					break;
			}
		}
		shownStart = start;
		shownEnd = end;
		return new Window(start, end, label);
	}

	static LocalDate parse(String text)
	{
		String s = text == null ? "" : text.trim();
		for (DateTimeFormatter f : new DateTimeFormatter[]{DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("d/M/yyyy")})
		{
			try
			{
				return LocalDate.parse(s, f);
			}
			catch (RuntimeException ignored)
			{
			}
		}
		return null;
	}
}
