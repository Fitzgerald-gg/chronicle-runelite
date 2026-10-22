/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

import chronicle.Kit.Line;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.Component;
import java.awt.FontMetrics;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import javax.swing.JComponent;
import javax.swing.JPanel;
import static chronicle.Feed.typeOf;
import static chronicle.Json.getIgnoreCase;
import static chronicle.Json.low;
import static chronicle.Json.obj;
import static chronicle.Json.safeLong;
import static chronicle.Json.str;
import static chronicle.Kit.ACCENT_LIFETIME;
import static chronicle.Kit.ACCENT_RED;
import static chronicle.Kit.ACCENT_SESSION;
import static chronicle.Kit.DARK;
import static chronicle.Kit.NAME_FLOOR;
import static chronicle.Kit.card;
import static chronicle.Kit.cardPlain;
import static chronicle.Kit.chaseRoom;
import static chronicle.Kit.column;
import static chronicle.Kit.day;
import static chronicle.Kit.fitLine;
import static chronicle.Kit.ghostRow;
import static chronicle.Kit.group;
import static chronicle.Kit.link;
import static chronicle.Kit.note;
import static chronicle.Kit.noted;
import static chronicle.Kit.pill;
import static chronicle.Kit.progress;
import static chronicle.Kit.row;
import static chronicle.Kit.rowMetrics;
import static chronicle.Kit.spaced;
import static chronicle.Kit.tip;
import static chronicle.Kit.vgap;
import static chronicle.Text.dated;
import static chronicle.Text.fmt;
import static chronicle.Text.share;

final class LogPage
{
	private static final String[] DROP_MARKS = {" · +", " +"};

	private final ChroniclePanel panel;

	LogPage(ChroniclePanel panel)
	{
		this.panel = panel;
	}

	private static String pageHeaderTip(JsonObject cl, String page)
	{
		if (cl == null)
		{
			return null;
		}
		JsonElement found = getIgnoreCase(obj(cl, "kc_lines"), page);
		if (found == null || !found.isJsonObject() || found.getAsJsonObject().size() == 0)
		{
			return null;
		}
		List<String> lines = new ArrayList<>();
		for (Entry<String, JsonElement> ln : found.getAsJsonObject().entrySet())
		{
			lines.add(ln.getKey());
			lines.add(fmt(safeLong(ln.getValue())));
		}
		return tip(page, lines);
	}

	private Map<String, GrindBook.PetChase> petChases(Collection<String> pets)
	{
		if (!panel.live.ready())
		{
			return Collections.emptyMap();
		}
		return panel.grindBook.petChases(panel.store.clogSnapshot(), panel.store.dropSources(), panel.store.trackersSnapshot(),
			panel.store.skillSheet(), panel.store.achievements(), pets);
	}

	private JPanel logInWindow(JPanel p)
	{
		List<JsonObject> got = new ArrayList<>();
		for (JsonObject e : panel.build.feed.all())
		{
			if ("COLLECTION".equals(typeOf(e)) && panel.window().inside(safeLong(e.get("ts"))))
			{
				got.add(e);
			}
		}
		if (got.isEmpty())
		{
			return noted(p, panel.window().phrase("Nothing new was logged"));
		}
		JPanel head = card("Collection log");
		head.add(row("Slots logged", fmt(got.size()), ACCENT_LIFETIME));
		spaced(p, head);
		for (JsonObject e : got)
		{
			JsonObject d = obj(e, "data");
			final String name = str(d, "itemName", "new item");
			JPanel line = row(name, Feed.stamp(e));
			link(line, () -> panel.openItem(name));
			p.add(line);
		}
		return p;
	}

	JPanel buildLog()
	{
		JPanel p = column();
		if (!panel.wholeRecord())
		{
			return logInWindow(p);
		}
		int[] standing = clogStanding();
		int fin = panel.live.clogFinished();
		JPanel head = card("Collection log");
		if (standing != null)
		{
			head.add(row(fmt(standing[0]) + " / " + fmt(standing[1]),
				Math.round(100f * standing[0] / standing[1]) + "%", ACCENT_LIFETIME));
			head.add(progress((float) standing[0] / standing[1]));
		}
		else if (fin > 0)
		{
			head.add(row("Slots obtained", fmt(fin), ACCENT_LIFETIME));
			head.add(row("Open your log in game once for the total", ""));
		}
		else
		{
			head.add(row("Open your log in game once to fill this in", ""));
		}
		spaced(p, head);

		ClogBook book = panel.build.book();
		JPanel pills = new JPanel(new GridLayout(0, 3, 3, 3));
		pills.setBackground(DARK);
		for (String tab : book.tax.keySet())
		{
			long[] of = book.tabStanding(tab);
			String hover = of == null ? null : tip(tab,
				"Obtained", fmt(of[0]),
				"Available", fmt(of[1]),
				"Share", share(of[0], of[1]));
			pills.add(pill(tab, tab.equals(panel.clogTab), 4, hover, () ->
			{
				panel.clogTab = tab;
				panel.clogPageSel = null;
				panel.rebuild();
			}));
		}
		spaced(p, pills);

		Map<String, List<String>> pages = book.tax.getOrDefault(panel.clogTab, new LinkedHashMap<>());
		for (Entry<String, List<String>> pg : pages.entrySet())
		{
			String page = pg.getKey();
			List<String> slots = pg.getValue();
			boolean[] lit = book.lit(page, slots);
			int got = ClogBook.held(lit);
			Long kc = book.kcs.get(low(page));
			boolean open = page.equals(panel.clogPageSel);
			boolean complete = got == slots.size() && !slots.isEmpty();
			JPanel rowP = row(page, got + "/" + slots.size()
				+ (kc != null && kc > 0 ? " · " + fmt(kc) + " kc" : ""),
				complete ? ACCENT_SESSION : null, complete);
			String lines = pageHeaderTip(book.clog, page);
			if (lines != null)
			{
				rowP.setToolTipText(lines);
			}
			link(rowP, () ->
			{
				panel.clogPageSel = open ? null : page;
				panel.rebuild();
			});
			p.add(rowP);
			if (open)
			{
				JPanel drill = cardPlain();
				boolean petPage = low(page).contains("pet");
				Map<String, LocalStore.PetRow> known = petPage
					? petsByName() : Collections.emptyMap();
				Map<String, GrindBook.PetChase> chases = petPage
					? petChases(slots) : Collections.emptyMap();
				List<List<JPanel>> detail = new ArrayList<>();
				boolean anyDetail = false;
				for (int i = 0; i < slots.size(); i++)
				{
					String key = low(slots.get(i));
					List<JPanel> d = petDetail(lit[i], known.get(key), chases.get(key));
					detail.add(d);
					anyDetail |= !d.isEmpty();
				}
				if (anyDetail)
				{
					spaced(drill, note("Click pet to see odds. Skilling odds are based "
						+ "on current level."), 3);
				}
				Map<String, Long> landed = panel.build.feed.landed();
				for (int i = 0; i < slots.size(); i++)
				{
					String slot = slots.get(i);
					JPanel r = row(slot, "",
						lit[i] || known.get(low(slot)) != null
							? ACCENT_SESSION : ACCENT_RED, true);
					Long when = landed.get(low(slot));
					if (when != null)
					{
						r.setToolTipText(tip(slot, "Landed", dated(when)));
					}
					drill.add(r);
					List<JPanel> d = detail.get(i);
					if (d.isEmpty())
					{
						continue;
					}
					String foldKey = "pets:" + page + ":" + low(slot);
					panel.folds(r, foldKey);
					if (panel.foldOpen(foldKey))
					{
						for (JPanel line : d)
						{
							drill.add(line);
						}
					}
				}
				spaced(p, drill, 3);
			}
		}

		if ("Other".equals(panel.clogTab))
		{
			List<String> strangers = book.strangers();
			if (!strangers.isEmpty())
			{
				p.add(vgap(6));
				p.add(group("NEW SINCE THIS RELEASE"));
				for (String pageName : strangers)
				{
					Map<String, Long> held = book.byPage.get(pageName);
					Long kc = book.kcs.get(pageName);
					p.add(row(ClogBook.prettyPage(pageName),
						fmt(held == null ? 0 : held.size()) + " held"
							+ (kc != null && kc > 0 ? " \u00b7 " + fmt(kc) + " kc" : "")));
				}
				p.add(ghostRow("Chronicle has no slot list for "
					+ (strangers.size() == 1 ? "this page" : "these pages")
					+ " yet, so only what you hold is known.", ""));
			}
		}
		return p;
	}

	private static String chaseSources(GrindBook.PetChase chase)
	{
		if (chase.activity != null)
		{
			return chase.activity + ", " + fmt(chase.kc) + " " + chase.unit;
		}
		return sourceLine(chase.sources, chase.sources.size(), "").whole();
	}

	private static Line sourceLine(List<GrindBook.PetSource> src, int kept, String mark)
	{
		Line l = new Line();
		for (int i = 0; i < kept; i++)
		{
			if (i > 0)
			{
				l.fixed(" · ");
			}
			l.name(src.get(i).boss);
			l.fixed(", kc " + fmt(src.get(i).kc));
		}
		if (kept < src.size())
		{
			l.fixed(mark + (src.size() - kept));
		}
		return l;
	}

	private static List<Integer> tailFirst(Line l, int from)
	{
		List<Integer> order = new ArrayList<>(l.names.subList(from, l.names.size()));
		Collections.reverse(order);
		return order;
	}

	private static String fitChase(GrindBook.PetChase chase, String share)
	{
		FontMetrics fm = rowMetrics();
		int avail = chaseRoom(share, fm);
		if (chase.activity != null)
		{
			Line l = new Line();
			l.name(chase.activity);
			l.fixed(", " + fmt(chase.kc) + " ");
			l.name(chase.unit);
			String s = fitLine(l, tailFirst(l, 0), NAME_FLOOR, fm, avail);
			if (s == null)
			{
				s = fitLine(l, tailFirst(l, 0), 1, fm, avail);
			}
			return s != null ? s : l.whole();
		}
		List<GrindBook.PetSource> src = chase.sources;
		if (src.isEmpty())
		{
			return "";
		}
		for (int kept = src.size(); kept >= 1; kept--)
		{
			for (String mark : kept < src.size() ? DROP_MARKS : new String[]{""})
			{
				Line l = sourceLine(src, kept, mark);
				String s = fitLine(l, tailFirst(l, 1), NAME_FLOOR, fm, avail);
				if (s != null)
				{
					return s;
				}
			}
		}
		Line l = sourceLine(src, 1, DROP_MARKS[DROP_MARKS.length - 1]);
		String s = fitLine(l, l.names, 1, fm, avail);
		return s != null ? s : l.whole();
	}

	private static String chaseTip(GrindBook.PetChase chase)
	{
		StringBuilder sb = new StringBuilder(pct(chase.percentileDry, "Under ", "Over ") + " of players have " + chase.pet
			+ " by this point. " + chaseSources(chase));
		if (chase.activity != null && chase.sources.size() > 1)
		{
			sb.append(", mostly ").append(low(chase.sources.get(0).boss));
		}
		if (chase.level > 0)
		{
			sb.append(". Priced at ").append(chase.level)
				.append(", the level you hold now, not the level each one was rolled at");
		}
		return sb.append(".").toString();
	}

	private static List<JPanel> petDetail(boolean lit, LocalStore.PetRow pet,
		GrindBook.PetChase chase)
	{
		List<JPanel> out = new ArrayList<>();
		if (pet != null)
		{
			StringBuilder line = new StringBuilder();
			if (pet.source != null && !pet.source.isEmpty())
			{
				line.append(pet.source);
				if (pet.kc > 0)
				{
					line.append(Sources.skill(pet.source.toUpperCase(Locale.ROOT)) != null
						? ", " + fmt(pet.kc) + " xp"
						: ", kc " + fmt(pet.kc));
				}
			}
			if (line.length() > 0)
			{
				out.add(ghostRow(line.toString(), pet.ts > 0
					? day(pet.ts) : ""));
			}
		}
		else if (!lit && chase != null)
		{
			String share = holdShare(chase);
			JPanel r = ghostRow(fitChase(chase, share), share,
				chase.percentileDry >= 90 ? ACCENT_RED : null);
			out.add(tipped(r, chaseTip(chase)));
		}
		return out;
	}

	private static JPanel tipped(JPanel r, String tip)
	{
		r.setToolTipText(tip);
		for (Component c : r.getComponents())
		{
			if (c instanceof JComponent)
			{
				((JComponent) c).setToolTipText(tip);
			}
		}
		return r;
	}

	private static String holdShare(GrindBook.PetChase chase)
	{
		return pct(chase.percentileDry, "<", ">") + " have";
	}

	private static String pct(double p, String under, String over)
	{
		return p < 1 ? under + "1%" : p > 99 ? over + "99%" : Math.round(p) + "%";
	}

	private Map<String, LocalStore.PetRow> petsByName()
	{
		Map<String, LocalStore.PetRow> out = new LinkedHashMap<>();
		for (LocalStore.PetRow r : panel.store.pets())
		{
			out.putIfAbsent(low(r.name), r);
		}
		return out;
	}

	int[] clogStanding()
	{
		int avail = panel.live.clogAvailable();
		return avail > 0 ? new int[]{panel.live.clogFinished(), avail} : null;
	}
}
