/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * An item's page carries the dates the record already holds: the dated roll
 * keeps one entry a day with its items, so when a thing first and last landed,
 * and on how many days, is a question it can answer.
 */
public class ItemDaysTest
{
	private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK);
	private static LocalStore store;

	@BeforeClass
	public static void mount() throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				javax.swing.UIManager.setLookAndFeel(
					new net.runelite.client.ui.laf.RuneLiteLAF());
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		File dir = new File(System.getProperty("java.io.tmpdir"), "chronicle-item-days");
		//noinspection ResultOfMethodCallIgnored
		dir.mkdirs();
		try (FileWriter w = new FileWriter(new File(dir, "runner.json")))
		{
			w.write("{\"drops\":{},\"loot_days\":{"
				+ "\"2026-08-03\":{\"items\":{\"4151\":{\"n\":\"Abyssal whip\",\"q\":1,\"v\":1500000}}},"
				+ "\"2026-09-10\":{\"items\":{\"4151\":{\"n\":\"Abyssal whip\",\"q\":1,\"v\":1500000}}},"
				+ "\"2026-09-11\":{\"items\":{\"560\":{\"n\":\"Death rune\",\"q\":100,\"v\":20000}}}}}");
		}
		store = new LocalStore(null, new Gson());
		store.load(dir, "runner");
	}

	private static long ms(String day)
	{
		return LocalDate.parse(day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
	}

	@Test
	public void theRollSaysWhenAnItemLanded()
	{
		assertArrayEquals(new long[]{ms("2026-08-03"), ms("2026-09-10"), 2, 2}, store.itemDays("Abyssal whip"));
		assertArrayEquals(new long[]{ms("2026-09-11"), ms("2026-09-11"), 1, 100}, store.itemDays("death rune"));
		assertArrayEquals(new long[4], store.itemDays("Dragon warhammer"));
	}

	private static JPanel page(String granularity, long[] days) throws Exception
	{
		return page(granularity, days, false);
	}

	private static JPanel page(String granularity, long[] days, boolean picture) throws Exception
	{
		PanelPreviewTest.StubPlugin stub = PanelPreviewTest.fixtureStub();
		stub.itemDays.put("Abyssal whip", days);
		final JPanel[] out = new JPanel[1];
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				ChroniclePanel p = new ChroniclePanel(stub);
				Field g = ChroniclePanel.class.getDeclaredField("histGranularity");
				g.setAccessible(true);
				g.set(p, granularity);
				Field copy = ChroniclePanel.class.getDeclaredField("drawingCopy");
				copy.setAccessible(true);
				copy.set(p, picture);
				Method m = ChroniclePanel.class.getDeclaredMethod("buildItemDetail", String.class);
				m.setAccessible(true);
				out[0] = (JPanel) m.invoke(p, "Abyssal whip");
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		return out[0];
	}

	@Test
	public void thePageCarriesItsDates() throws Exception
	{
		JPanel page = page("Lifetime", new long[]{ms("2026-08-03"), ms("2026-09-10"), 2});
		assertEquals(FULL.format(LocalDate.parse("2026-08-03")), beside(page, "First dropped"));
		assertEquals(FULL.format(LocalDate.parse("2026-09-10")), beside(page, "Last dropped"));
		assertEquals("2", beside(page, "Days it landed"));
	}

	@Test
	public void oneDayIsOneLine() throws Exception
	{
		JPanel page = page("Lifetime", new long[]{ms("2026-09-11"), ms("2026-09-11"), 1});
		assertEquals(FULL.format(LocalDate.parse("2026-09-11")), beside(page, "Dropped on"));
		assertNull(beside(page, "Days it landed"));
	}

	/**
	 * Where the dated days hold fewer than the record has had, the first of them
	 * is the day the roll began and not the day the item first dropped.
	 */
	@Test
	public void datesTheRollCannotStandBehindAreLeftOff() throws Exception
	{
		JPanel page = page("Lifetime", new long[]{ms("2026-08-03"), ms("2026-09-10"), 2, 0});
		assertNull(beside(page, "First dropped"));
	}

	/** And a picture carries none: the first is near enough the install day. */
	@Test
	public void aPictureCarriesNoDropDates() throws Exception
	{
		JPanel page = page("Lifetime", new long[]{ms("2026-08-03"), ms("2026-09-10"), 2}, true);
		assertNull(beside(page, "First dropped"));
	}

	@Test
	public void aNarrowedPageKeepsLifetimeDatesOffIt() throws Exception
	{
		JPanel page = page("Week", new long[]{ms("2026-08-03"), ms("2026-09-10"), 2});
		assertNull(beside(page, "First dropped"));
	}

	private static String beside(Component c, String left)
	{
		List<Component> flat = new ArrayList<>();
		flatten(c, flat);
		for (Component k : flat)
		{
			if (k instanceof JPanel && ((JPanel) k).getLayout() instanceof BorderLayout)
			{
				BorderLayout l = (BorderLayout) ((JPanel) k).getLayout();
				Component mid = l.getLayoutComponent(BorderLayout.CENTER);
				Component east = l.getLayoutComponent(BorderLayout.EAST);
				if (mid instanceof JLabel && left.equals(((JLabel) mid).getText())
					&& east instanceof JLabel)
				{
					return ((JLabel) east).getText();
				}
			}
		}
		return null;
	}

	private static void flatten(Component c, List<Component> out)
	{
		out.add(c);
		if (c instanceof Container)
		{
			for (Component k : ((Container) c).getComponents())
			{
				flatten(k, out);
			}
		}
	}
}
