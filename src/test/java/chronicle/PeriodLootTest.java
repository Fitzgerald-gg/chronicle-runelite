/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the BSD 2-Clause
 * License (see LICENSE) are met.
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.game.ItemManager;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A loot page under a period lists what the period paid, not everything the
 * source has ever paid. The dated roll kept a day's items as one heap beside its
 * sources' totals, so a week could say what Mad Angel paid and not what it paid
 * it in, and the page listed the lifetime under the week's heading. Each day now
 * keeps its items per source, and the days written before that are split where
 * the record proves whose each item was.
 */
public class PeriodLootTest
{
	private static final String RSN = "Tester";

	@Rule
	public TemporaryFolder dir = new TemporaryFolder();

	@BeforeClass
	public static void headless() throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				javax.swing.UIManager.setLookAndFeel(new net.runelite.client.ui.laf.RuneLiteLAF());
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
	}

	private static final LocalDate D1 = LocalDate.now().minusDays(3);
	private static final LocalDate D2 = LocalDate.now().minusDays(2);
	private static final LocalDate D3 = LocalDate.now().minusDays(1);

	private static JsonObject item(String n, long q, long v)
	{
		JsonObject o = new JsonObject();
		o.addProperty("n", n);
		o.addProperty("q", q);
		o.addProperty("v", v);
		return o;
	}

	private static JsonObject total(long loots, long value)
	{
		JsonObject o = new JsonObject();
		o.addProperty("loots", loots);
		o.addProperty("value", value);
		return o;
	}

	private static JsonObject bagItem(String name, long qty, long value)
	{
		JsonObject o = new JsonObject();
		o.addProperty("name", name);
		o.addProperty("qty", qty);
		o.addProperty("value", value);
		return o;
	}

	/**
	 * Three days written before the roll kept items per source: one source
	 * alone (D1), two sources whose items only one of them has ever dropped
	 * (D2), and two sources on an item both have dropped (D3), which no record
	 * can split.
	 */
	private File oldJournal() throws Exception
	{
		JsonObject drops = new JsonObject();
		JsonObject angel = new JsonObject();
		angel.add("22446", bagItem("Hallowfell", 1, 1_900_000));
		angel.add("995", bagItem("Coins", 54_436, 54_436));
		angel.add("361", bagItem("Tuna", 4, 400));
		drops.add("Mad Angel", src(angel));
		JsonObject herbi = new JsonObject();
		herbi.add("199", bagItem("Grimy guam leaf", 40, 4_000));
		herbi.add("361", bagItem("Tuna", 2, 200));
		drops.add("Herbiboar", src(herbi));

		JsonObject days = new JsonObject();
		JsonObject d1 = total(2, 300);
		d1.add("sources", obj("Herbiboar", total(2, 300)));
		d1.add("items", obj("199", item("Grimy guam leaf", 3, 300)));
		days.add(D1.toString(), d1);
		JsonObject d2 = total(2, 600);
		JsonObject s2 = new JsonObject();
		s2.add("Herbiboar", total(1, 100));
		s2.add("Mad Angel", total(1, 500));
		d2.add("sources", s2);
		JsonObject i2 = new JsonObject();
		i2.add("199", item("Grimy guam leaf", 1, 100));
		i2.add("995", item("Coins", 500, 500));
		d2.add("items", i2);
		days.add(D2.toString(), d2);
		JsonObject d3 = total(2, 200);
		JsonObject s3 = new JsonObject();
		s3.add("Herbiboar", total(1, 100));
		s3.add("Mad Angel", total(1, 100));
		d3.add("sources", s3);
		d3.add("items", obj("361", item("Tuna", 2, 200)));
		days.add(D3.toString(), d3);

		JsonObject root = new JsonObject();
		root.addProperty("schema", 1);
		root.addProperty("rsn", RSN);
		root.add("drops", drops);
		root.add("loot_days", days);
		File d = dir.getRoot();
		Files.write(new File(d, LocalStore.slug(RSN) + ".json").toPath(),
			new Gson().toJson(root).getBytes(StandardCharsets.UTF_8));
		return d;
	}

	private static JsonObject src(JsonObject items)
	{
		JsonObject s = new JsonObject();
		long loots = 0;
		long value = 0;
		for (String k : items.keySet())
		{
			loots++;
			value += items.getAsJsonObject(k).get("value").getAsLong();
		}
		s.addProperty("kc", 0);
		s.addProperty("loots", loots);
		s.addProperty("value", value);
		s.add("items", items);
		return s;
	}

	private static JsonObject obj(String key, JsonObject value)
	{
		JsonObject o = new JsonObject();
		o.add(key, value);
		return o;
	}

	private static LocalStore store(File d)
	{
		ItemManager im = Mockito.mock(ItemManager.class);
		Mockito.when(im.canonicalize(Mockito.anyInt())).thenAnswer(i -> i.getArgument(0));
		Mockito.when(im.getItemPrice(Mockito.anyInt())).thenReturn(10);
		Mockito.when(im.getItemComposition(Mockito.anyInt())).thenAnswer(i ->
		{
			net.runelite.api.ItemComposition c = Mockito.mock(net.runelite.api.ItemComposition.class);
			Mockito.when(c.getName()).thenReturn((int) i.getArgument(0) == 199 ? "Grimy guam leaf" : "Coins");
			return c;
		});
		LocalStore s = new LocalStore(im, new Gson());
		s.load(d, RSN);
		return s;
	}

	private static long qty(Map<String, List<LocalStore.BagItem>> by, String source, String item)
	{
		for (LocalStore.BagItem b : by.getOrDefault(source, new ArrayList<>()))
		{
			if (b.name.equals(item))
			{
				return b.qty;
			}
		}
		return 0;
	}

	private static JsonObject loot(String source, int id, int qty)
	{
		JsonObject d = new JsonObject();
		d.addProperty("source", source);
		JsonArray arr = new JsonArray();
		JsonObject it = new JsonObject();
		it.addProperty("id", id);
		it.addProperty("quantity", qty);
		arr.add(it);
		d.add("items", arr);
		return d;
	}

	/** A drop is filed under its source in the day as well as in the heap. */
	@Test
	public void aDropIsFiledUnderItsSourceInTheDay() throws Exception
	{
		LocalStore s = store(dir.getRoot());
		s.record("LOOT", loot("Mad Angel", 995, 50), RSN);
		s.record("LOOT", loot("Herbiboar", 199, 2), RSN);
		s.record("LOOT", loot("Herbiboar", 995, 7), RSN);
		LocalDate today = LocalDate.now();
		Map<String, List<LocalStore.BagItem>> day = s.itemsBySource(today, today);
		assertEquals(50, qty(day, "Mad Angel", "Coins"));
		assertEquals(7, qty(day, "herbiboar", "Coins"));
		assertEquals(2, qty(day, "Herbiboar", "Grimy guam leaf"));
		// and the sitting keeps the same
		Map<String, List<LocalStore.BagItem>> sitting = s.itemsBySource(null, null);
		assertEquals(day.get("Herbiboar").size(), sitting.get("Herbiboar").size());
	}

	/**
	 * The days written before: a day with one source is its own, and on a day
	 * with two an item only one of them has ever dropped is that one's; an item
	 * both have dropped leaves its day whole.
	 */
	@Test
	public void theDaysBeforeAreSplitWhereTheRecordProvesWhose() throws Exception
	{
		LocalStore s = store(oldJournal());
		assertEquals(3, qty(s.itemsBySource(D1, D1), "Herbiboar", "Grimy guam leaf"));
		Map<String, List<LocalStore.BagItem>> d2 = s.itemsBySource(D2, D2);
		assertEquals(1, qty(d2, "Herbiboar", "Grimy guam leaf"));
		assertEquals(500, qty(d2, "Mad Angel", "Coins"));
		Map<String, List<LocalStore.BagItem>> d3 = s.itemsBySource(D3, D3);
		assertEquals("a Tuna both have dropped was given to one", 0,
			qty(d3, "Mad Angel", "Tuna") + qty(d3, "Herbiboar", "Tuna"));
	}

	/**
	 * An item two of the day's sources have both dropped is nobody's, even where
	 * handing it to one would come to every total: a guess that happens to add up
	 * is still a guess.
	 */
	@Test
	public void aGuessThatAddsUpIsStillNotWritten() throws Exception
	{
		File d = dir.getRoot();
		JsonObject angel = new JsonObject();
		angel.add("361", bagItem("Tuna", 2, 200));
		JsonObject herbi = new JsonObject();
		herbi.add("361", bagItem("Tuna", 2, 200));
		herbi.add("199", bagItem("Grimy guam leaf", 1, 0));
		JsonObject drops = new JsonObject();
		drops.add("Mad Angel", src(angel));
		drops.add("Herbiboar", src(herbi));
		JsonObject day = total(2, 200);
		JsonObject srcs = new JsonObject();
		srcs.add("Mad Angel", total(1, 200));
		srcs.add("Herbiboar", total(1, 0));
		day.add("sources", srcs);
		JsonObject items = new JsonObject();
		items.add("361", item("Tuna", 2, 200));
		items.add("199", item("Grimy guam leaf", 1, 0));
		day.add("items", items);
		JsonObject root = new JsonObject();
		root.addProperty("schema", 1);
		root.addProperty("rsn", RSN);
		root.add("drops", drops);
		root.add("loot_days", obj(D1.toString(), day));
		Files.write(new File(d, LocalStore.slug(RSN) + ".json").toPath(),
			new Gson().toJson(root).getBytes(StandardCharsets.UTF_8));
		Map<String, List<LocalStore.BagItem>> d1 = store(d).itemsBySource(D1, D1);
		assertEquals(d1.toString(), 0, qty(d1, "Mad Angel", "Tuna") + qty(d1, "Herbiboar", "Tuna"));
	}

	/** A day whose sources' totals the split would not come to is left whole. */
	@Test
	public void aSplitThatDoesNotComeToTheTotalsIsNotWritten() throws Exception
	{
		File d = oldJournal();
		File f = new File(d, LocalStore.slug(RSN) + ".json");
		JsonObject root = new Gson().fromJson(new String(Files.readAllBytes(f.toPath()),
			StandardCharsets.UTF_8), JsonObject.class);
		root.getAsJsonObject("loot_days").getAsJsonObject(D1.toString())
			.getAsJsonObject("sources").getAsJsonObject("Herbiboar").addProperty("value", 299);
		Files.write(f.toPath(), new Gson().toJson(root).getBytes(StandardCharsets.UTF_8));
		assertEquals(0, qty(store(d).itemsBySource(D1, D1), "Herbiboar", "Grimy guam leaf"));
	}

	/**
	 * Today, written by an older build and split on load, then recorded into:
	 * the drop lands once in the source's rows, not twice through a shared copy.
	 */
	@Test
	public void aSplitTodayTakesTheNextDropOnce() throws Exception
	{
		File d = dir.getRoot();
		JsonObject day = total(1, 100);
		day.add("sources", obj("Herbiboar", total(1, 100)));
		day.add("items", obj("199", item("Grimy guam leaf", 1, 100)));
		JsonObject root = new JsonObject();
		root.addProperty("schema", 1);
		root.addProperty("rsn", RSN);
		root.add("loot_days", obj(LocalDate.now().toString(), day));
		Files.write(new File(d, LocalStore.slug(RSN) + ".json").toPath(),
			new Gson().toJson(root).getBytes(StandardCharsets.UTF_8));
		LocalStore s = store(d);
		s.record("LOOT", loot("Herbiboar", 199, 2), RSN);
		LocalDate today = LocalDate.now();
		assertEquals(3, qty(s.itemsBySource(today, today), "Herbiboar", "Grimy guam leaf"));
		assertEquals("3", s.lootBetween(today, today).items.get(0)[1]);
	}

	/** The split is made once: loading it again changes nothing. */
	@Test
	public void theSplitIsMadeOnce() throws Exception
	{
		File d = oldJournal();
		store(d).flush(d);
		File f = new File(d, LocalStore.slug(RSN) + ".json");
		String once = new Gson().fromJson(new String(Files.readAllBytes(f.toPath()),
			StandardCharsets.UTF_8), JsonObject.class).get("loot_days").toString();
		store(d).flush(d);
		String twice = new Gson().fromJson(new String(Files.readAllBytes(f.toPath()),
			StandardCharsets.UTF_8), JsonObject.class).get("loot_days").toString();
		assertEquals(once, twice);
	}

	// ------------------------------------------------------------------
	// the pages
	// ------------------------------------------------------------------

	private ChroniclePanel panel(LocalDate from, LocalDate to) throws Exception
	{
		PanelPreviewTest.StubPlugin stub = PanelPreviewTest.journalStub(oldJournal().getPath(), RSN);
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(stub));
		set(hold[0], "histGranularity", from == null ? "Lifetime" : "Day");
		set(hold[0], "histFrom", from);
		set(hold[0], "histTo", to);
		if (from != null)
		{
			set(hold[0], "histCursor", to);
		}
		return hold[0];
	}

	private static void set(ChroniclePanel p, String name, Object value) throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(p, value);
	}

	private static List<String> page(ChroniclePanel p, String method, String name) throws Exception
	{
		final List<String> said = new ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Method m = ChroniclePanel.class.getDeclaredMethod(method, String.class);
				m.setAccessible(true);
				collect((Component) m.invoke(p, name), said);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		return said;
	}

	private static void collect(Component c, List<String> out)
	{
		if (c instanceof JLabel && ((JLabel) c).getText() != null && !((JLabel) c).getText().isEmpty())
		{
			out.add(((JLabel) c).getText());
		}
		if (c instanceof Container)
		{
			for (Component k : ((Container) c).getComponents())
			{
				collect(k, out);
			}
		}
	}

	private static String after(List<String> said, String label)
	{
		int at = said.indexOf(label);
		return at >= 0 && at + 1 < said.size() ? said.get(at + 1) : null;
	}

	/**
	 * The source page lists what the period paid: Mad Angel's Coins from D2, not
	 * the Hallowfell of its lifetime, and what fell on the day kept whole (D3's
	 * shared Tuna) as Other, so the list still comes to the Worth above it.
	 */
	@Test
	public void theSourcePageListsWhatThePeriodPaid() throws Exception
	{
		List<String> said = page(panel(D1, D3), "buildSourceDetail", "Mad Angel");
		assertTrue(said.toString(), said.contains("Coins ×500"));
		assertFalse("the lifetime listed under a period: " + said, said.contains("Hallowfell"));
		assertEquals(said.toString(), "100 gp", after(said, "Other"));
		assertFalse("the old caveat is still there: " + said,
			String.join(" ", said).contains("everything this source has ever paid"));
	}

	/** A period the source paid nothing in says so, not the lifetime. */
	@Test
	public void aPeriodItPaidNothingInSaysSo() throws Exception
	{
		List<String> said = page(panel(D1, D1), "buildSourceDetail", "Mad Angel");
		assertTrue(said.toString(), String.join(" ", said).contains("Nothing from Mad Angel inside"));
		assertFalse(said.toString(), said.contains("Hallowfell"));
	}

	/** A period reaching back before the roll says the day it is read from. */
	@Test
	public void aPeriodBeforeTheRollSaysWhereItStarts() throws Exception
	{
		List<String> said = page(panel(D1.minusDays(5), D3), "buildSourceDetail", "Mad Angel");
		assertTrue(said.toString(), String.join(" ", said).contains("Loot since "));
		List<String> inside = page(panel(D1, D3), "buildSourceDetail", "Mad Angel");
		assertFalse(inside.toString(), String.join(" ", inside).contains("Loot since "));
	}

	/** Lifetime is the ledger, as it was: every item, no Other. */
	@Test
	public void theLifetimePageIsTheLedger() throws Exception
	{
		List<String> said = page(panel(null, null), "buildSourceDetail", "Mad Angel");
		assertTrue(said.toString(), said.stream().anyMatch(l -> l.startsWith("Hallowfell")));
		assertNull(said.toString(), after(said, "Other"));
	}

	/** The item page's sources are the period's, and what no source kept is Other. */
	@Test
	public void theItemPageListsThePeriodsSources() throws Exception
	{
		List<String> guam = page(panel(D1, D3), "buildItemDetail", "Grimy guam leaf");
		assertEquals(guam.toString(), "Herbiboar", guam.get(guam.indexOf("FROM") + 1));
		assertNull(guam.toString(), after(guam, "Other"));
		List<String> tuna = page(panel(D1, D3), "buildItemDetail", "Tuna");
		assertEquals("the shared Tuna was put under a source: " + tuna, "×2", after(tuna, "Other"));
		assertFalse(tuna.toString(), tuna.contains("Mad Angel"));
	}
}
