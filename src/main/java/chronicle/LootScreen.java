/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import chronicle.Board.Kind;
import chronicle.LocalStore.BagItem;
import chronicle.LocalStore.SourceRow;
import chronicle.LocalStore.UntakenRow;
import chronicle.Period.Window;
import com.google.gson.JsonObject;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.BooleanSupplier;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.util.AsyncBufferedImage;
import static chronicle.Feed.*;
import static chronicle.Json.*;
import static chronicle.LocalStore.kindOf;
import static chronicle.Pictures.*;
import static chronicle.Reference.*;
import static chronicle.Ui.*;

final class LootScreen
{
	private final ChroniclePanel ui;
	private final Board board;
	private final ChroniclePlugin plugin;
	private final Period period;

	LootScreen(ChroniclePanel ui, Board board)
	{
		this.ui = ui;
		this.board = board;
		this.plugin = board.plugin;
		this.period = board.period;
	}

	JPanel buildDrops()
	{
		JPanel p = column();
		List<JPanel> axes = new ArrayList<>();
		axes.add(toggle(dropsLeftBehind ? "Left behind" : "Received", relens(() -> dropsLeftBehind = !dropsLeftBehind)));
		if (!dropsLeftBehind || period.whole())
		{
			axes.add(toggle(dropsByKind
				? (dropsLeftBehind ? "By item" : "By kind") : "By source", relens(() -> dropsByKind = !dropsByKind)));
		}
		final boolean canAskOnTask = !dropsLeftBehind && dropsByKind && board.everOnTask();
		if (canAskOnTask)
		{
			axes.add(toggle(onTaskOnly ? "On task" : "All", relens(() -> onTaskOnly = !onTaskOnly)));
		}
		JPanel lens = new JPanel(new GridLayout(1, axes.size(), 3, 3));
		lens.setBackground(DARK);
		for (JPanel a : axes)
		{
			lens.add(a);
		}
		spaced(p, lens);
		if (canAskOnTask && onTaskOnly)
		{
			long[] w = board.windowMs();
			List<BagItem> taskBag = plugin.onTaskLoot(w[0], w[1], null, period.whole());
			if (taskBag.isEmpty())
			{
				return noted(p, board.inside("No task closed"));
			}
			return kindLens(p, period.whole() ? "On-task loot"
				: "Tasks closed in " + board.window().label, taskBag, "ontask:");
		}
		if (!period.whole())
		{
			return dropsInWindow(p);
		}

		if (dropsLeftBehind)
		{
			return buildLeftBehind(p);
		}
		if (dropsByKind)
		{
			return buildLootByKind(p);
		}
		List<SourceRow> sources = new ArrayList<>(board.sources());
		sources.sort(Comparator.comparingLong((SourceRow r) -> r.value).reversed());
		if (sources.isEmpty())
		{
			return noted(p, "Drops appear here as you play: every kill, priced as it lands.");
		}
		long everyDrop = 0;
		long everyValue = 0;
		for (SourceRow r : sources)
		{
			everyDrop += r.loots;
			everyValue += r.value;
		}
		JPanel lifeHead = tallyCard("Drops received", "Drops", fmt(everyDrop), ACCENT, everyValue);
		lifeHead.add(row("Sources", fmt(sources.size())));
		spaced(p, lifeHead);
		for (SourceRow r : firstN(sources, dropsShown))
		{
			boolean killed = board.isKillSource(r.name);
			String sub = (killed ? fmt(board.standingKills(r)) + " kc" : count(r.loots, "drop"))
				+ (r.pb != null ? " · PB " + pb(r.pb) : "");
			listCard(p, row(r.name, gps(r.value), ACCENT), sub,
				r.loots > 0 ? perOne(r.value, r.loots, killed) : "", () -> ui.openSource(r.name));
		}
		ui.more(p, sources.size(), dropsShown, false, n -> dropsShown = n);
		return p;
	}

	JPanel dropsInWindow(JPanel p)
	{
		Window win = board.window();
		LocalStore.LootWindow sitting = period.session() ? plugin.sessionLootWindow() : null;
		long rollFrom = plugin.lootRollFrom();
		long fromMs = startMs(win.start);
		if (sitting == null && rollFrom <= 0)
		{
			return noted(p, Board.UNDATED);
		}
		if (sitting == null && rollFrom > fromMs)
		{
			LocalDate began = dayOf(rollFrom);
			return noted(p, "The dated loot roll begins " + began.format(FULL_DAY)
				+ ", which is inside " + board.periodInSentence() + ". Naming the part it can see "
				+ "as the whole period would be worse than saying nothing.");
		}
		LocalStore.LootWindow w = sitting != null ? sitting
			: plugin.lootBetween(win.start, win.end);
		if (!dropsLeftBehind && dropsByKind)
		{
			if (w.items.isEmpty())
			{
				return noted(p, board.inside("Nothing taken"));
			}
			return kindLens(p, win.label, bagOf(w.items), "win:");
		}
		List<String[]> ranked = dropsLeftBehind ? w.leftItems : w.sources;
		if (ranked.isEmpty())
		{
			return noted(p, board.inside("Nothing " + (dropsLeftBehind ? "left behind" : "taken")));
		}
		JPanel head = dropsLeftBehind ? tallyCard("Left behind", "Items", fmt(w.left), RED, w.leftValue)
			: tallyCard("Drops received", "Drops", fmt(w.loots), ACCENT, w.value);
		if (dropsLeftBehind)
		{
			head.add(row("Kills that left one", fmt(w.leftKills)));
		}
		spaced(p, head);
		final String key = dropsLeftBehind ? "win:left" : "win:source";
		final int cap = ui.cap(key, ROW_CAP);
		for (String[] r : firstN(ranked, cap))
		{
			JPanel line = row(r[0], qtyGp(safeParse(r[1]), safeParse(r[2])));
			final String name = r[0];
			link(line, () ->
			{
				if (dropsLeftBehind)
				{
					ui.openItem(name);
				}
				else
				{
					ui.openSourceLoose(name);
				}
			});
			p.add(line);
		}
		ui.drillMore(p, key, ranked.size(), cap);
		return p;
	}

	static List<BagItem> bagOf(List<String[]> rows)
	{
		List<BagItem> bag = new ArrayList<>();
		for (String[] r : rows)
		{
			bag.add(new BagItem(0, r[0], safeParse(r[1]), safeParse(r[2])));
		}
		return bag;
	}

	JPanel buildLootByKind(JPanel p)
	{
		final List<BagItem> bag = plugin.allLoot();
		if (bag.isEmpty())
		{
			return noted(p, "Drops appear here as you play: every kill, priced as it lands.");
		}
		return kindLens(p, "Everything dropped", bag, "all:");
	}

	void addKindRows(JPanel p, List<BagItem> bag)
	{
		for (Kind k : board.kindsOf(bag))
		{
			JPanel r = row(k.name, qtyGp(k.qty, k.value), ACCENT);
			r.setToolTipText(count(k.distinct, "distinct item"));
			link(r, () ->
			{
				lootKind = k.name;
				ui.rebuildInPlace();
			});
			p.add(r);
		}
	}

	void dearestRow(JPanel head, List<BagItem> bag)
	{
		BagItem top = most(bag, b -> b.value);
		if (top == null)
		{
			return;
		}
		final String name = top.name;
		JPanel r = row("Dearest", name + " · " + gps(top.value));
		link(r, () -> ui.openItem(name));
		head.add(r);
	}

	JPanel kindLens(JPanel p, String title, List<BagItem> bag,
		String key)
	{
		final long[] sum = board.tallyOf(bag);
		if (lootKind != null)
		{
			return kindDrill(p, bag, key);
		}
		JPanel head = bagCard(title, bag, sum);
		dearestRow(head, bag);
		spaced(p, head);
		LinkedHashMap<String, BooleanSupplier> ways =
			new LinkedHashMap<>();
		ways.put("These kinds", () -> copyPicture(lootPicture(title, bag, sum, true)));
		ways.put("Every item", () -> copyPicture(lootPicture(title, bag, sum, false), true));
		p.add(copyHeader("Drops", ways));
		addKindRows(p, bag);
		return p;
	}

	JPanel kindDrill(JPanel p, List<BagItem> bag, String key)
	{
		final List<BagItem> kept = ofKind(bag);
		final long[] mine = board.tallyOf(kept);
		spaced(p, bagCard(lootKind, kept, mine));
		p.add(backToKinds(kept.size()));
		if (kept.isEmpty())
		{
			return noted(p, "Nothing of this kind here.");
		}
		p.add(copyHeader(lootKind, () -> copyPicture(
			lootPicture(lootKind, kept, mine, false), true)));
		int cap = ui.cap(key + lootKind, ROW_CAP);
		addBagRows(p, firstN(kept, cap));
		ui.drillMore(p, key + lootKind, kept.size(), cap);
		return p;
	}

	JPanel buildLeftBehind(JPanel p)
	{
		List<UntakenRow> rows = plugin.untakenSources();
		rows.sort(Comparator.comparingLong((UntakenRow r) -> r.value).reversed());
		long totalQty = 0;
		long totalVal = 0;
		for (UntakenRow r : rows)
		{
			totalQty += r.qty;
			totalVal += r.value;
		}
		if (rows.isEmpty())
		{
			return noted(p, "What you walk past gets counted here, priced at the "
				+ "moment you declined it.");
		}
		List<UntakenRow> items = plugin.untakenItems();
		items.sort(Comparator.comparingLong((UntakenRow r) -> r.value).reversed());
		JPanel head = tallyCard("Left behind", "Items", fmt(totalQty), RED, totalVal);
		head.add(row(dropsByKind ? "Distinct items" : "Sources",
			fmt(dropsByKind ? items.size() : rows.size())));
		spaced(p, head);
		if (dropsByKind && items.isEmpty())
		{
			return noted(p, "Nothing walked past has been priced yet.");
		}
		final boolean byItem = dropsByKind;
		List<UntakenRow> list = byItem ? items : rows;
		String key = byItem ? "left:item" : "left:source";
		final int cap = ui.cap(key, ROW_CAP);
		for (UntakenRow r : firstN(list, cap))
		{
			listCard(p, row(r.name, gps(r.value), RED),
				byItem ? "\u00d7" + fmt(r.qty) : fmt(r.qty) + " left", r.qty > 0 ? perOne(r.value, r.qty, false) : "",
				() -> ui.showLeftBehind(byItem ? null : r.name, byItem ? r.name : null));
		}
		ui.drillMore(p, key, list.size(), cap);
		return p;
	}

	JPanel lootPicture(String title, List<BagItem> bag, long[] sum, boolean kinds)
	{
		JPanel page = column();
		spaced(page, bagCard(title, bag, sum));
		if (kinds)
		{
			addKindRows(page, bag);
		}
		else
		{
			addBagRows(page, bag);
		}
		return page;
	}

	Runnable relens(Runnable change)
	{
		return () ->
		{
			change.run();
			lootKind = null;
			ui.rebuildInPlace();
		};
	}

	JPanel bagCard(String title, List<BagItem> bag, long[] sum)
	{
		JPanel head = tallyCard(title, "Items", fmt(sum[0]), ACCENT, sum[1]);
		head.add(row("Distinct items", fmt(bag.size())));
		return head;
	}

	JPanel backToKinds(int held)
	{
		return backRow("< All kinds", count(held, "item"), () ->
		{
			lootKind = null;
			ui.rebuildInPlace();
		});
	}

	void addBagRows(JPanel p, List<BagItem> bag)
	{
		for (BagItem b : bag)
		{
			JPanel r = row(named(b.name, b.qty),
				b.value > 0 ? gps(b.value) : "");
			link(r, () -> ui.openItem(b.name));
			p.add(r);
		}
	}

	List<BagItem> ofKind(List<BagItem> bag)
	{
		if (lootKind == null)
		{
			return bag;
		}
		List<BagItem> kept = new ArrayList<>();
		for (BagItem b : bag)
		{
			String k = ItemKinds.kindOf(b.name);
			if (Board.UNFILED.equals(lootKind) ? k == null : lootKind.equals(k))
			{
				kept.add(b);
			}
		}
		return kept;
	}

	boolean dropsLeftBehind;

	String lootKind;

	String lootTask;

	boolean dropsByKind;

	boolean onTaskOnly;

	int dropsShown = ROW_CAP;

	void reset()
	{
		dropsShown = ROW_CAP;
		lootKind = null;
		lootTask = null;
	}

	void forget()
	{
		grindsCache = null;
		grindsFetching = false;
	}

	JPanel buildLeftBehindDetail(String source, String item)
	{
		JPanel p = column();
		p.add(ui.backRow(null));
		p.add(vgap(4));

		if (source != null)
		{
			List<BagItem> bag = plugin.untakenItemsOf(source);
			UntakenRow left = find(plugin.untakenSources(), u -> u.name, source, true);
			spaced(p, tallyCard(source.toUpperCase(Locale.ROOT), "Left on the floor",
				count(left == null ? 0 : left.qty, "item"), RED, left == null ? 0 : left.value));
			if (bag.isEmpty())
			{
				return noted(p, "The count above is older than the itemised record. "
					+ "What this source leaves behind is listed here from now on.");
			}
			p.add(group("Declined"));
			for (BagItem b : bag)
			{
				JPanel r = row(named(b.name, b.qty),
					gps(b.value), RED);
				link(r, () -> ui.showLeftBehind(null, b.name));
				p.add(r);
			}
			return p;
		}

		List<UntakenRow> sources = plugin.untakenSourcesOf(item);
		UntakenRow held = find(plugin.untakenItems(), u -> u.name, item, true);
		spaced(p, tallyCard(item.toUpperCase(Locale.ROOT), "Left behind",
			"×" + fmt(held == null ? 0 : held.qty), RED, held == null ? 0 : held.value));
		if (sources.isEmpty())
		{
			return noted(p, "No source itemised for this yet.");
		}
		p.add(group("Left where"));
		for (UntakenRow r : sources)
		{
			JPanel row = row(r.name, "×" + qtyGp(r.qty, r.value), RED);
			link(row, () -> ui.showLeftBehind(r.name, null));
			p.add(row);
		}
		return p;
	}

	JPanel buildItemDetail(String name)
	{
		JPanel p = column();
		long got = 0;
		long worth = 0;
		int found = 0;
		final List<Object[]> srcs = new ArrayList<>();
		for (SourceRow r : board.sources())
		{
			for (BagItem b : plugin.sourceItems(r.name))
			{
				if (b.name.equalsIgnoreCase(name))
				{
					got += b.qty;
					worth += b.value;
					if (found == 0 && b.itemId > 0)
					{
						found = b.itemId;
					}
					srcs.add(new Object[]{r.name, b.qty, b.value});
				}
			}
		}
		final long[] inWindow = period.whole() ? null : Board.rowOf(board.lootWindow().items, name);
		long other = 0;
		long otherValue = 0;
		if (inWindow != null)
		{
			srcs.clear();
			other = inWindow[0];
			otherValue = inWindow[1];
			String[] headRow = Board.rowFor(board.lootWindow().items, name, false);
			String spelled = headRow != null ? headRow[0] : name;
			for (Map.Entry<String, List<BagItem>> e : board.periodItems().entrySet())
			{
				for (BagItem b : e.getValue())
				{
					if (b.name.equals(spelled))
					{
						srcs.add(new Object[]{e.getKey(), b.qty, b.value});
						other -= b.qty;
						otherValue -= b.value;
					}
				}
			}
		}
		srcs.sort((a, b) -> Long.compare((long) b[1], (long) a[1]));
		final long qty = inWindow != null ? inWindow[0] : got;
		final long value = inWindow != null ? inWindow[1] : worth;
		final int itemId = found;
		spaced(p, ui.backRow(() -> ui.copyPage(() -> buildItemDetail(name))), 4);
		JPanel head = card(name);
		if (itemId > 0)
		{
			JLabel slot = new JLabel();
			slot.setPreferredSize(new Dimension(36, 32));
			slot.setAlignmentX(Component.LEFT_ALIGNMENT);
			AsyncBufferedImage img = plugin.items().getImage(itemId,
				(int) Math.min(Integer.MAX_VALUE, Math.max(1, qty)), qty > 1);
			img.addTo(slot);
			head.add(slot);
		}
		final boolean hasTask = board.taskItemsEver().containsKey(properName(name));
		long[] tw = board.windowMs();
		long[] mine = inWindow == null ? board.taskItemsEver().get(properName(name))
			: plugin.onTaskItems(tw[0], tw[1]).getOrDefault(properName(name), new long[2]);
		if (hasTask && onTaskOnly)
		{
			head.add(row("Obtained on task", "×" + fmt(mine[0]), ACCENT));
			if (inWindow == null || lootSince() == null)
			{
				head.add(row("All sources", "×" + fmt(qty)));
			}
			JPanel priced = worthRow(mine[1]);
			priced.setToolTipText("Priced as the drop landed, or in bulk on the day "
				+ "the history was imported");
			head.add(priced);
		}
		else
		{
			head.add(row("Obtained", "×" + fmt(qty), ACCENT));
			if (value > 0)
			{
				head.add(worthRow(value));
			}
		}
		if (inWindow == null && !ui.drawingCopy)
		{
			long[] days = plugin.itemDays(name);
			days[2] = days.length > 3 && days[3] < got ? 0 : days[2];
			if (days[2] == 1)
			{
				head.add(row("Dropped on", dated(days[0])));
			}
			else if (days[2] > 1)
			{
				head.add(row("First dropped", dated(days[0])));
				head.add(row("Last dropped", dated(days[1])));
				head.add(row("Days it landed", fmt(days[2])));
			}
		}
		p.add(head);
		String since = inWindow == null || hasTask && onTaskOnly ? null : lootSince();
		if (inWindow != null && sinceLine(since) != null)
		{
			p.add(vgap(4));
			p.add(note(sinceLine(since)));
		}
		p.add(vgap(6));
		if (hasTask)
		{
			JPanel hold = column();
			hold.add(toggle(onTaskOnly ? "On task" : "All", relens(() -> onTaskOnly = !onTaskOnly)));
			hold.add(vgap(6));
			p.add(hold);
		}
		if (hasTask && onTaskOnly)
		{
			return byTaskRows(p, properName(name));
		}
		if (srcs.isEmpty() && other <= 0)
		{
			return inWindow != null ? nothing(p, "dropped", since)
				: noted(p, "The journal hasn't seen this item drop yet.");
		}
		p.add(group("From"));
		final int srcCap = Math.max(ui.drawingCopy ? COPY_MOST : 40, ui.cap("item:src:" + name, 0));
		for (Object[] s : firstN(srcs, srcCap))
		{
			JPanel r = row((String) s[0], "×" + fmt((long) s[1])
				+ tail((long) s[2]));
			final String src = (String) s[0];
			link(r, () -> ui.openSource(src));
			p.add(r);
		}
		ui.drillMore(p, "item:src:" + name, srcs.size(), srcCap);
		addOther(p, "×" + fmt(Math.max(0, other)) + tail(Math.max(0, otherValue)), other > 0 || otherValue > 0);
		return p;
	}

	String lootSince()
	{
		long from = plugin.lootDetailFrom();
		Window w = board.window();
		return period.session() || from > 0 && from <= startMs(w.start) ? null
			: from <= 0 ? Board.UNDATED
			: !ui.drawingCopy ? "Loot since " + dayOf(from).format(FULL_DAY)
			: "Loot is dated for " + (dayOf(from).isAfter(w.end) ? "none" : "only part") + " of "
			+ board.periodInSentence() + ".";
	}

	String sinceLine(String since)
	{
		return since != null || period.whole() || !ui.drawingCopy ? since
			: "The figures above are " + board.periodInSentence() + "'s.";
	}

	JPanel nothing(JPanel p, String what, String since)
	{
		return since != null ? p : noted(p, board.inside("Nothing " + what));
	}

	static void addOther(JPanel p, String figure, boolean show)
	{
		if (show)
		{
			JPanel r = ghostRow("Other", figure);
			r.setToolTipText("Dropped on days the record kept whole rather than by source");
			p.add(r);
		}
	}

	String properName(String typed)
	{
		String key = keyOf(board.taskItemsEver().keySet(), typed);
		return key == null ? typed : key;
	}

	JPanel byTaskRows(JPanel p, String name)
	{
		long[] w = board.windowMs();
		List<Object[]> split = plugin.onTaskItemByTask(name, w[0], w[1]);
		if (split.isEmpty())
		{
			return noted(p, board.inside("No task paid this"));
		}
		p.add(group("By task"));
		final int taskCap = Math.max(ui.drawingCopy ? COPY_MOST : 40, ui.cap("item:task:" + name, 0));
		for (Object[] t : firstN(split, taskCap))
		{
			p.add(row("Task: " + t[0], "×" + fmt((long) t[1]) + tail((long) t[2])));
		}
		ui.drillMore(p, "item:task:" + name, split.size(), taskCap);
		return p;
	}

	void addKillSources(JPanel head, SourceRow sr, long headline)
	{
		if (headline < 0)
		{
			return;
		}
		String want = LocalStore.chatKind(sr.name);
		Map<String, Long> rows = new LinkedHashMap<>();
		putKind(rows, "Kill Log", LocalStore.killLogCounts(board.clogNow()), want);
		putKind(rows, "Said in chat", plugin.chatKills(), want);
		putKind(rows, "Collection log", LocalStore.pageKillLines(board.clogNow()), want);
		putKind(rows, "Running count", plugin.anchoredKills(), want);
		if (sr.loots > 0)
		{
			rows.put("Drops logged", (long) sr.loots);
		}
		Long dropped = board.taskKillsEver().get(sr.name);
		if (dropped != null && dropped > 0)
		{
			rows.put("Dropped on task", dropped);
		}
		boolean accounted = false;
		for (Long v : rows.values())
		{
			accounted |= v != null && v.longValue() == headline;
		}
		if (rows.size() < 2 || !accounted)
		{
			return;
		}
		final String key = "kcsrc:" + sr.name;
		head.add(ui.quietHead("What says so", "", key));
		if (!ui.foldOpen(key))
		{
			return;
		}
		for (Entry<String, Long> e : rows.entrySet())
		{
			head.add(row(e.getKey(), fmt(e.getValue())));
		}
	}

	static void putKind(Map<String, Long> rows, String label,
		Map<String, Long> from, String want)
	{
		for (Entry<String, Long> e : from.entrySet())
		{
			if (LocalStore.chatKind(e.getKey()).equals(want) && e.getValue() > 0)
			{
				rows.put(label, e.getValue());
				return;
			}
		}
	}

	void addAssignments(JPanel p, String npc)
	{
		long[] w = board.windowMs();
		List<LocalStore.Assignment> was = plugin.onTaskAssignments(npc, w[0], w[1]);
		if (was.isEmpty())
		{
			return;
		}
		p.add(group("Killed on task"));
		int cap = ui.cap("ontask:src:" + npc, ROW_CAP);
		for (LocalStore.Assignment a : firstN(was, cap))
		{
			p.add(row("Task: " + a.task, fmt(a.killsHere)));
		}
		ui.drillMore(p, "ontask:src:" + npc, was.size(), cap);
		p.add(vgap(6));
	}

	void addFloorRow(JPanel head, String name)
	{
		for (UntakenRow u : plugin.untakenSources())
		{
			if (u.qty > 0 && u.name.equalsIgnoreCase(name))
			{
				JPanel r = row("Left behind", qtyGp(u.qty, u.value)
					+ (u.kills > 0 ? " · " + count(u.kills, "kill") : ""));
				r.setToolTipText("Open what was left on the floor");
				link(r, () -> ui.showLeftBehind(u.name, null));
				head.add(r);
				return;
			}
		}
	}

	JPanel buildSourceDetail(String name)
	{
		JPanel p = column();
		final SourceRow sr = find(board.sources(), r -> r.name, name, false);
		String[] row = period.whole() ? null : Board.rowFor(board.lootWindow().sources, sr != null ? sr.name : name, sr != null);
		long[] inWindow = period.whole() ? null
			: row == null ? new long[2] : new long[]{safeParse(row[1]), safeParse(row[2])};
		String own = row != null ? row[0] : sr != null ? sr.name : name;
		final List<BagItem> bag = inWindow == null ? plugin.sourceItems(own)
			: new ArrayList<>(board.periodItems().getOrDefault(own, new ArrayList<>()));
		bag.sort(Comparator.comparingLong((BagItem b) -> b.value).reversed());
		final long other = inWindow == null ? 0 : inWindow[1] - board.tallyOf(bag)[1];
		final boolean unfiled = other > 0 || inWindow != null && !period.session()
			&& plugin.unfiledSources(board.window().start, board.window().end).contains(own);
		spaced(p, ui.backRow(() -> ui.copyPage(() -> buildSourceDetail(name))), 4);
		JPanel head = card(name);
		if (sr != null)
		{
			boolean killed = board.isKillSource(sr.name);
			long shown = inWindow != null ? inWindow[0]
				: killed ? board.standingKills(sr) : sr.loots;
			head.add(row(killed ? "Kills" : "Times looted", fmt(shown), ACCENT));
			long worth = inWindow != null ? inWindow[1] : sr.value;
			long over = inWindow != null ? inWindow[0] : sr.loots;
			head.add(row("Worth", gps(worth)
				+ (over > 0 ? " · " + perOne(worth, over, killed) : "")));
			if (inWindow == null)
			{
				addKillSources(head, sr, killed ? shown : -1);
				addFloorRow(head, sr.name);
			}
			for (Entry<String, Long> pbLine : board.pageLines(sr.name, "pb_lines"))
			{
				head.add(row(pbLine.getKey(), clock(pbLine.getValue())));
			}
			for (Entry<String, Long> ln : board.logLines(sr.name))
			{
				head.add(row(ln.getKey(), fmt(ln.getValue())));
			}
			if (sr.pb != null)
			{
				JsonObject rec = board.records().get(low(sr.name));
				JsonObject recData = rec != null ? rec.getAsJsonObject("data") : null;
				JPanel best = row("Personal best", pb(sr.pb) + (rec != null
					? " · set " + day(asLong(rec.get("ts"))) : ""));
				if (recData != null && recData.has("was"))
				{
					best.setToolTipText("Was " + pb(recData.get("was").getAsDouble()));
				}
				head.add(best);
			}
			double[] timed = inWindow == null ? new double[]{sr.timed, sr.timeSum}
				: board.lootWindow().times.getOrDefault(own, new double[2]);
			if (timed[0] > 0)
			{
				head.add(row("Average kill", pb(timed[1] / timed[0]) + " · "
					+ fmt((long) timed[0]) + " timed"));
			}
			long here = board.minutesAt(sr.name, inWindow == null ? board.counters() : board.periodCounters());
			if (here > 0 && board.minutesCoverPeriod())
			{
				boolean rate = killed && shown > 0 && here >= 30;
				head.add(row("Time here", hoursMinutes(here)
					+ (rate ? " · " + rateText(shown * 60.0 / here) + " kills/h" : "")));
			}
			if (sr.firstMs > 0 && !ui.drawingCopy)
			{
				head.add(row("Tracked since",
					day(sr.firstMs)));
			}
			if (grindsCache == null && !grindsFetching && !ui.drawingCopy)
			{
				grindsFetching = true;
				plugin.fetchGrinds(rows2 -> SwingUtilities.invokeLater(() ->
				{
					grindsFetching = false;
					if (rows2 == null)
					{
						return;
					}
					grindsCache = rows2;
					if (ui.showing(ChroniclePanel.Page.SOURCE, sr.name))
					{
						ui.rebuildInPlace();
					}
				}));
			}
			if (grindsCache != null && !ui.drawingCopy)
			{
				for (GrindBook.GrindRow g : grindsCache)
				{
					if (g.boss.equalsIgnoreCase(sr.name))
					{
						head.add(row("Chasing " + g.item,
							fmt(g.kc) + " / " + fmt(g.rate) + " kc",
							g.percentileDry >= 90 ? RED : null));
						break;
					}
				}
			}
		}
		spaced(p, head);
		String since = inWindow == null ? null : lootSince();
		if (sinceLine(since) != null)
		{
			spaced(p, note(sinceLine(since)), 5);
		}
		if (sr != null)
		{
			addAssignments(p, sr.name);
		}
		if (!bag.isEmpty() || unfiled)
		{
			JPanel grid = new JPanel(new GridLayout(0, 5, 3, 3));
			grid.setBackground(DARK);
			int sprites = 0;
			for (BagItem b : bag)
			{
				if (b.itemId <= 0)
				{
					continue;
				}
				if (sprites++ >= 10)
				{
					break;
				}
				grid.add(ui.sprite(b.itemId, b.name, b.qty));
			}
			if (sprites > 0)
			{
				spaced(p, grid, 5);
			}
			p.add(group("Loot"));
			int cap = ui.drawingCopy ? COPY_MOST : ui.cap(name, 25);
			addBagRows(p, firstN(bag, cap));
			if (bag.size() > cap)
			{
				p.add(vgap(3));
			}
			ui.drillMore(p, name, bag.size(), cap);
			addOther(p, gps(Math.max(0, other)), unfiled);
			return p;
		}
		return sr == null ? noted(p, "The journal has no drops from this source yet.")
			: inWindow != null ? nothing(p, "from " + name, since)
			: noted(p, "Items fill in as you play. The journal prices each drop the "
			+ "moment it lands.");
	}

	List<GrindBook.GrindRow> grindsCache;

	boolean grindsFetching;
}
