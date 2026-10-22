/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

import chronicle.LocalStore.BagItem;
import chronicle.LocalStore.SourceRow;
import chronicle.LocalStore.UntakenRow;
import java.awt.GridLayout;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import javax.swing.JPanel;
import static chronicle.Copy.copyPicture;
import static chronicle.Feed.most;
import static chronicle.Kit.ACCENT_LIFETIME;
import static chronicle.Kit.ACCENT_RED;
import static chronicle.Kit.DARK;
import static chronicle.Kit.ROW_CAP;
import static chronicle.Kit.column;
import static chronicle.Kit.copyHeader;
import static chronicle.Kit.link;
import static chronicle.Kit.linkRow;
import static chronicle.Kit.listCard;
import static chronicle.Kit.noted;
import static chronicle.Kit.row;
import static chronicle.Kit.spaced;
import static chronicle.Kit.tallyCard;
import static chronicle.Kit.toggle;
import static chronicle.Kit.value;
import static chronicle.LocalStore.kindOf;
import static chronicle.Period.FULL_DAY;
import static chronicle.Period.dayOf;
import static chronicle.Period.startMs;
import static chronicle.Text.count;
import static chronicle.Text.fmt;
import static chronicle.Text.gp;
import static chronicle.Text.gps;
import static chronicle.Text.named;
import static chronicle.Text.pb;
import static chronicle.Text.qtyGp;

final class LootPage
{
	static final String UNFILED = "Everything else";

	static final String UNDATED = "No loot has been dated yet. The roll keeps one entry a "
		+ "day and starts with the next drop that lands.";

	private final ChroniclePanel panel;

	LootPage(ChroniclePanel panel)
	{
		this.panel = panel;
	}

	private JPanel dropsInWindow(JPanel p)
	{
		Period win = panel.window();
		long rollFrom = panel.build.lootDays().rollFrom();
		if (!win.session && rollFrom <= 0)
		{
			return noted(p, UNDATED);
		}
		if (!win.session && rollFrom > startMs(win.start))
		{
			LocalDate began = dayOf(rollFrom);
			return noted(p, "The dated loot roll begins " + began.format(FULL_DAY)
				+ ", which is inside " + panel.window().inSentence() + ". Naming the part it can see "
				+ "as the whole period would be worse than saying nothing.");
		}
		LocalStore.LootWindow w = panel.build.lootWindow();
		if (!panel.dropsLeftBehind && panel.dropsByKind)
		{
			if (w.items.isEmpty())
			{
				return noted(p, panel.window().phrase("Nothing taken"));
			}
			return kindLens(p, win.label, bagOf(w.items), "win:");
		}
		List<LocalStore.Row> ranked = panel.dropsLeftBehind ? w.leftItems : w.sources;
		if (ranked.isEmpty())
		{
			return noted(p, panel.window().phrase("Nothing " + (panel.dropsLeftBehind ? "left behind" : "taken")));
		}
		JPanel head = panel.dropsLeftBehind ? tallyCard("Left behind", "Items", fmt(w.left), ACCENT_RED, w.leftValue)
			: tallyCard("Drops received", "Drops", fmt(w.loots), ACCENT_LIFETIME, w.value);
		if (panel.dropsLeftBehind)
		{
			head.add(row("Kills that left one", fmt(w.leftKills)));
		}
		spaced(p, head);
		final String key = panel.dropsLeftBehind ? "win:left" : "win:source";
		final int cap = panel.drillShown.getOrDefault(key, ROW_CAP);
		for (LocalStore.Row r : Sources.firstN(ranked, cap))
		{
			JPanel line = row(r.name, qtyGp(r.qty, r.value));
			final String name = r.name;
			link(line, () ->
			{
				if (panel.dropsLeftBehind)
				{
					panel.openItem(name);
				}
				else
				{
					panel.openSourceLoose(name);
				}
			});
			p.add(line);
		}
		panel.drillMore(p, key, ranked.size(), cap);
		return p;
	}

	private static List<BagItem> bagOf(List<LocalStore.Row> rows)
	{
		List<BagItem> bag = new ArrayList<>();
		for (LocalStore.Row r : rows)
		{
			bag.add(new BagItem(0, r.name, r.qty, r.value));
		}
		return bag;
	}

	JPanel buildDrops()
	{
		JPanel p = column();
		List<JPanel> axes = new ArrayList<>();
		axes.add(toggle(panel.dropsLeftBehind ? "Left behind" : "Received", panel.relens(() -> panel.dropsLeftBehind = !panel.dropsLeftBehind)));
		if (!panel.dropsLeftBehind || panel.wholeRecord())
		{
			axes.add(toggle(panel.dropsByKind
				? (panel.dropsLeftBehind ? "By item" : "By kind") : "By source", panel.relens(() -> panel.dropsByKind = !panel.dropsByKind)));
		}
		final boolean canAskOnTask = !panel.dropsLeftBehind && panel.dropsByKind && everOnTask();
		if (canAskOnTask)
		{
			axes.add(toggle(panel.onTaskOnly ? "On task" : "All", panel.relens(() -> panel.onTaskOnly = !panel.onTaskOnly)));
		}
		JPanel lens = new JPanel(new GridLayout(1, axes.size(), 3, 3));
		lens.setBackground(DARK);
		for (JPanel a : axes)
		{
			lens.add(a);
		}
		spaced(p, lens);
		if (canAskOnTask && panel.onTaskOnly)
		{
			long[] w = panel.window().ms();
			List<BagItem> taskBag = panel.store.tasks(w[0], w[1], null, panel.wholeRecord()).loot();
			if (taskBag.isEmpty())
			{
				return noted(p, panel.window().phrase("No task closed"));
			}
			return kindLens(p, panel.wholeRecord() ? "On-task loot"
				: "Tasks closed in " + panel.window().label, taskBag, "ontask:");
		}
		if (!panel.wholeRecord())
		{
			return dropsInWindow(p);
		}

		if (panel.dropsLeftBehind)
		{
			return buildLeftBehind(p);
		}
		if (panel.dropsByKind)
		{
			return buildLootByKind(p);
		}
		List<SourceRow> sources = new ArrayList<>(panel.build.sources());
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
		JPanel lifeHead = tallyCard("Drops received", "Drops", fmt(everyDrop), ACCENT_LIFETIME, everyValue);
		lifeHead.add(row("Sources", fmt(sources.size())));
		spaced(p, lifeHead);
		for (SourceRow r : Sources.firstN(sources, panel.dropsShown))
		{
			boolean killed = panel.build.kills.isKillSource(r.name);
			String sub = (killed ? fmt(panel.build.kills.standingKills(r)) + " kc" : count(r.loots, "drop"))
				+ (r.pb != null ? " · PB " + pb(r.pb) : "");
			listCard(p, row(r.name, gps(r.value), ACCENT_LIFETIME), sub,
				r.loots > 0 ? perOne(r.value, r.loots, killed) : "", () -> panel.openSource(r.name));
		}
		panel.more(p, sources.size(), panel.dropsShown, false, n -> panel.dropsShown = n);
		return p;
	}

	private JPanel buildLootByKind(JPanel p)
	{
		final List<BagItem> bag = panel.store.allLoot();
		if (bag.isEmpty())
		{
			return noted(p, "Drops appear here as you play: every kill, priced as it lands.");
		}
		return kindLens(p, "Everything dropped", bag, "all:");
	}

	private void dearestRow(JPanel head, List<BagItem> bag)
	{
		BagItem top = most(bag, b -> b.value);
		if (top == null)
		{
			return;
		}
		final String name = top.name;
		JPanel r = row("Dearest", name + " · " + gps(top.value));
		link(r, () -> panel.openItem(name));
		head.add(r);
	}

	private JPanel kindLens(JPanel p, String title, List<BagItem> bag,
		String key)
	{
		final long[] sum = LocalStore.tallyOf(bag);
		if (panel.lootKind != null)
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

	private JPanel buildLeftBehind(JPanel p)
	{
		List<UntakenRow> rows = panel.store.untaken().sources;
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
		List<UntakenRow> items = panel.store.untaken().items;
		items.sort(Comparator.comparingLong((UntakenRow r) -> r.value).reversed());
		JPanel head = tallyCard("Left behind", "Items", fmt(totalQty), ACCENT_RED, totalVal);
		head.add(row(panel.dropsByKind ? "Distinct items" : "Sources",
			fmt(panel.dropsByKind ? items.size() : rows.size())));
		spaced(p, head);
		if (panel.dropsByKind && items.isEmpty())
		{
			return noted(p, "Nothing walked past has been priced yet.");
		}
		final boolean byItem = panel.dropsByKind;
		List<UntakenRow> list = byItem ? items : rows;
		String key = byItem ? "left:item" : "left:source";
		final int cap = panel.drillShown.getOrDefault(key, ROW_CAP);
		for (UntakenRow r : Sources.firstN(list, cap))
		{
			listCard(p, row(r.name, gps(r.value), ACCENT_RED),
				byItem ? "\u00d7" + fmt(r.qty) : fmt(r.qty) + " left", r.qty > 0 ? perOne(r.value, r.qty, false) : "",
				() -> panel.showLeftBehind(byItem ? null : r.name, byItem ? r.name : null));
		}
		panel.drillMore(p, key, list.size(), cap);
		return p;
	}

	boolean everOnTask()
	{
		return !panel.build.taskItems().isEmpty();
	}

	static String perOne(long value, long n, boolean killed)
	{
		return gp(value / Math.max(1, n)) + (killed ? " gp/drop" : " gp each");
	}

	void addKindRows(JPanel p, List<BagItem> bag)
	{
		for (Kind k : kindsOf(bag))
		{
			JPanel r = row(k.name, qtyGp(k.qty, k.value), ACCENT_LIFETIME);
			r.setToolTipText(count(k.distinct, "distinct item"));
			link(r, () ->
			{
				panel.lootKind = k.name;
				panel.rebuildInPlace();
			});
			p.add(r);
		}
	}

	JPanel kindDrill(JPanel p, List<BagItem> bag, String key)
	{
		final List<BagItem> kept = ofKind(bag);
		final long[] mine = LocalStore.tallyOf(kept);
		spaced(p, bagCard(panel.lootKind, kept, mine));
		p.add(backToKinds(kept.size()));
		if (kept.isEmpty())
		{
			return noted(p, "Nothing of this kind here.");
		}
		p.add(copyHeader(panel.lootKind, () -> copyPicture(
			lootPicture(panel.lootKind, kept, mine, false), true)));
		int cap = panel.drillShown.getOrDefault(key + panel.lootKind, ROW_CAP);
		addBagRows(p, Sources.firstN(kept, cap));
		panel.drillMore(p, key + panel.lootKind, kept.size(), cap);
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

	private List<Kind> kindsOf(List<BagItem> bag)
	{
		Map<String, Kind> by = new LinkedHashMap<>();
		for (BagItem b : bag)
		{
			String k = ItemKinds.kindOf(b.name);
			Kind row = by.computeIfAbsent(k == null ? UNFILED : k, Kind::new);
			row.qty += b.qty;
			row.value += b.value;
			row.distinct++;
		}
		List<Kind> out = new ArrayList<>(by.values());
		out.sort(Comparator.comparingLong((Kind k) -> k.value).reversed());
		return out;
	}

	JPanel bagCard(String title, List<BagItem> bag, long[] sum)
	{
		JPanel head = tallyCard(title, "Items", fmt(sum[0]), ACCENT_LIFETIME, sum[1]);
		head.add(row("Distinct items", fmt(bag.size())));
		return head;
	}

	private JPanel backToKinds(int held)
	{
		return linkRow("< All kinds", count(held, "item"), () ->
		{
			panel.lootKind = null;
			panel.rebuildInPlace();
		});
	}

	void addBagRows(JPanel p, List<BagItem> bag)
	{
		for (BagItem b : bag)
		{
			JPanel r = row(named(b.name, b.qty),
				b.value > 0 ? gps(b.value) : "");
			link(r, () -> panel.openItem(b.name));
			p.add(r);
		}
	}

	private List<BagItem> ofKind(List<BagItem> bag)
	{
		if (panel.lootKind == null)
		{
			return bag;
		}
		List<BagItem> kept = new ArrayList<>();
		for (BagItem b : bag)
		{
			String k = ItemKinds.kindOf(b.name);
			if (UNFILED.equals(panel.lootKind) ? k == null : panel.lootKind.equals(k))
			{
				kept.add(b);
			}
		}
		return kept;
	}

	private static final class Kind
	{
		final String name;
		long qty;
		long value;
		int distinct;

		Kind(String name)
		{
			this.name = name;
		}
	}
}
