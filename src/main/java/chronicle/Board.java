/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import chronicle.HistoryLog.Baseline;
import chronicle.LocalStore.SourceRow;
import chronicle.Period.Window;
import chronicle.SlayerLog.SlayerJourney;
import com.google.gson.JsonObject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.function.Supplier;
import javax.swing.JPanel;
import javax.swing.SwingWorker;
import lombok.RequiredArgsConstructor;
import static chronicle.Feed.*;
import static chronicle.Json.*;
import static chronicle.Reference.*;
import static chronicle.Ui.*;

final class Board
{
	static final int FEED_SCAN_DEEP = 4000;
	static final int JOURNAL_DEEP = 20_000;
	static final String UNFILED = "Everything else";
	static final String UNDATED = "No loot has been dated yet. The roll keeps one entry a "
		+ "day and starts with the next drop that lands.";
	private static final int HISTORY_FEED_SCAN = 2000;
	static final long EVER_FROM = Long.MIN_VALUE / 2;
	static final long EVER_TO = Long.MAX_VALUE / 2;

	final ChroniclePlugin plugin;
	final Period period;
	final LocalStore store;
	private final Runnable onHistory;
	final CounterQuery counts = new CounterQuery(this);
	final LootQuery loot = new LootQuery(this);
	final KillQuery kills = new KillQuery(this);
	final SkillQuery skill = new SkillQuery(this);
	final DayQuery days = new DayQuery(this);
	final LogQuery clog = new LogQuery(this);
	private final Map<String, Object> memo = new HashMap<>();
	boolean rollUsed;

	TreeMap<LocalDate, Baseline> historySpine;
	List<JsonObject> historyFeed = new ArrayList<>();
	SlayerJourney historyJourney;
	LocalDate historyDay;
	long historyFeedTs;
	boolean historyGathering;
	private int historyEpoch;

	@RequiredArgsConstructor
	static final class Span
	{
		final Baseline opening;
		final Baseline earliest;
		final Baseline closing;
	}

	Board(ChroniclePlugin plugin, Period period, Runnable onHistory)
	{
		this.plugin = plugin;
		this.period = period;
		this.store = plugin.store();
		this.onHistory = onHistory;
	}

	void reset()
	{
		memo.clear();
		rollUsed = false;
	}

	void forget()
	{
		days.searchFeed = null;
		days.searchFeedSpine = null;
		historySpine = null;
		historyFeed = new ArrayList<>();
		historyJourney = null;
		historyDay = null;
		historyFeedTs = 0;
		historyEpoch++;
		historyGathering = false;
	}

	@SuppressWarnings("unchecked")
	<T> T memo(String key, Supplier<T> make)
	{
		if (!memo.containsKey(key))
		{
			memo.put(key, make.get());
		}
		return (T) memo.get(key);
	}

	void gatherHistory()
	{
		if (historyGathering)
		{
			return;
		}
		historyGathering = true;
		final int epoch = historyEpoch;
		new SwingWorker<History, Void>()
		{
			@Override
			protected History doInBackground()
			{
				return new History(plugin.historyBaselines(), store.feedNewest(HISTORY_FEED_SCAN), plugin.slayerJourney(),
					LocalDate.now());
			}

			@Override
			protected void done()
			{
				if (epoch != historyEpoch)
				{
					return;
				}
				historyGathering = false;
				History h;
				try
				{
					h = get();
				}
				catch (Exception e)
				{
					return;
				}
				historySpine = h.spine;
				historyFeed = h.feed;
				historyJourney = h.journey;
				historyDay = h.day;
				historyFeedTs = newestTs(historyFeed);
				onHistory.run();
			}
		}.execute();
	}

	Window window()
	{
		return period.window(plugin.sessionStart(),
			historySpine == null || historySpine.isEmpty() ? null : historySpine.firstKey());
	}

	Interval range()
	{
		if (period.whole())
		{
			return new Interval(EVER_FROM, EVER_TO);
		}
		if (period.session())
		{
			long began = plugin.sessionStart();
			return new Interval(began > 0 ? began : startMs(LocalDate.now()), System.currentTimeMillis());
		}
		Window w = window();
		return new Interval(startMs(w.start), startMs(w.end.plusDays(1)) - 1);
	}

	boolean insideWindow(long ts)
	{
		if (period.whole())
		{
			return true;
		}
		if (ts <= 0)
		{
			return !period.session();
		}
		return range().holds(ts);
	}

	String inside(String said)
	{
		return said + " inside " + periodInSentence() + ".";
	}

	String periodInSentence()
	{
		String label = window().label;
		return label.startsWith("This ") ? Character.toLowerCase(label.charAt(0)) + label.substring(1) : label;
	}

	boolean periodReachesToday()
	{
		return !window().end.isBefore(LocalDate.now());
	}

	boolean closesOnTheClient(Entry<LocalDate, Baseline> from, LocalDate start, LocalDate end)
	{
		return from != null && !end.isBefore(LocalDate.now())
			&& (period.session() || !from.getKey().isBefore(start.minusDays(1)));
	}

	Span span()
	{
		return memo("span", () ->
		{
			if (historySpine == null)
			{
				gatherHistory();
				return null;
			}
			if (historySpine.isEmpty())
			{
				return null;
			}
			Window w = window();
			Entry<LocalDate, Baseline> from = HistoryLog.windowStart(historySpine, w.start, w.end);
			Entry<LocalDate, Baseline> at = historySpine.floorEntry(w.end);
			if (at == null || from == null
				|| at.getKey().equals(from.getKey()) && !closesOnTheClient(from, w.start, w.end))
			{
				return null;
			}
			return new Span(HistoryLog.stateAt(historySpine, from.getKey()),
				HistoryLog.earliest(historySpine, at.getKey()),
				HistoryLog.stateAt(historySpine, at.getKey()));
		});
	}

	JPanel noPeriod()
	{
		return note(historySpine == null ? "Reading your history..."
			: "Nothing closed inside " + periodInSentence() + ". A period is the distance "
				+ "between two baselines, and this window holds fewer than two.");
	}

	String notCounting(boolean kills)
	{
		if (period.whole() || period.session() || historySpine == null)
		{
			return null;
		}
		for (Entry<LocalDate, Baseline> e : historySpine.entrySet())
		{
			if (!(kills ? e.getValue().kcs : e.getValue().counters).isEmpty())
			{
				return window().end.isBefore(e.getKey())
					? "The record keeps no " + (kills ? "kill counts" : "counters")
						+ " before " + e.getKey().format(FULL_DAY) + "."
					: null;
			}
		}
		return null;
	}

	Map<String, Long> closingNow(Map<String, Long> closing, Map<String, Long> live)
	{
		if (closing == null || live == null || live.isEmpty() || !periodReachesToday())
		{
			return closing;
		}
		Map<String, Long> out = new HashMap<>(closing);
		live.forEach((k, v) ->
		{
			if (v != null)
			{
				out.merge(k, v, Math::max);
			}
		});
		return out;
	}

	Map<String, Long> closingSkills(Map<String, Long> skills, boolean live)
	{
		Map<String, Long> close = new HashMap<>(skills);
		if (live)
		{
			plugin.skillSheet().forEach((k, v) ->
			{
				if (v != null && v.xp > 0)
				{
					close.merge(k, v.xp, Math::max);
				}
			});
		}
		return close;
	}

	List<SourceRow> sources()
	{
		return memo("sources", store::dropSources);
	}

	JsonObject clogNow()
	{
		return memo("clog", store::clogSnapshot);
	}

	JsonObject achievements()
	{
		return memo("achievements", plugin::achievements);
	}

	@RequiredArgsConstructor
	private static final class History
	{
		final TreeMap<LocalDate, Baseline> spine;
		final List<JsonObject> feed;
		final SlayerJourney journey;
		final LocalDate day;
	}
}
