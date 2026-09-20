/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A collection log page the GAME has and this release's list does not.
 *
 * <p>Jagex adds a page and a plugin update takes as long as it takes. Until
 * this, the reader's own capture of that page sat on their disk and appeared
 * nowhere on screen: the board draws the bundled taxonomy and nothing else, so
 * a page outside it did not exist as far as the panel was concerned.
 *
 * <p>It cannot be filed under a tab, because the scrape records a page's title
 * and not which tab it sat under, so it is shown once under Other, where the
 * game itself puts what does not fit, and labelled as unknown rather than
 * dressed up as a page with a slot list.
 */
public class NewPagesSelfHealTest
{
	// by_cat holds a page the bundled taxonomy has never heard of, with two
	// items held on it, exactly as a scrape of a new page would leave it.
	private static final String JOURNAL =
		"{\"schema\":1,\"rsn\":\"Somebody\",\"drops\":{},"
		+ "\"collection_log\":{\"finished\":3,\"available\":1717,"
		+ "\"kcs\":{\"Amoxliatl\":12},"
		+ "\"by_cat\":{\"Some Future Boss\":{\"Tome of earth\":1,\"Huberte\":1}},"
		+ "\"clog_items\":{\"Tome of earth\":1,\"Huberte\":1}},"
		+ "\"trackers\":{},\"skills\":{},\"feed\":[]}";

	private static List<String> logRows(String tab) throws Exception
	{
		File dir = new File(System.getProperty("java.io.tmpdir"), "chronicle-newpages");
		//noinspection ResultOfMethodCallIgnored
		dir.mkdirs();
		try (FileWriter w = new FileWriter(new File(dir, "somebody.json")))
		{
			w.write(JOURNAL);
		}
		PanelPreviewTest.StubPlugin s = PanelPreviewTest.journalStub(dir.getPath(), "Somebody");
		final List<String> out = new ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				ChroniclePanel p = new ChroniclePanel(s);
				Field tf = ChroniclePanel.class.getDeclaredField("clogTab");
				tf.setAccessible(true);
				tf.set(p, tab);
				Method m = ChroniclePanel.class.getDeclaredMethod("buildLog");
				m.setAccessible(true);
				List<Component> flat = new ArrayList<>();
				flatten((Component) m.invoke(p), flat);
				for (Component c : flat)
				{
					if (c instanceof JLabel && ((JLabel) c).getText() != null)
					{
						out.add(((JLabel) c).getText());
					}
				}
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		return out;
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

	private static boolean has(List<String> said, String what)
	{
		for (String s : said)
		{
			if (s.contains(what))
			{
				return true;
			}
		}
		return false;
	}

	@Test
	public void aPageThisReleaseDoesNotKnowIsStillShown() throws Exception
	{
		List<String> said = logRows("Other");
		assertTrue("a captured page outside the bundled list vanished: " + said,
			has(said, "Some Future Boss"));
		assertTrue("shown, but without saying how much of it is held: " + said,
			has(said, "2 held"));
		assertTrue("shown, but without saying why it has no slot list: " + said,
			has(said, "NEW SINCE THIS RELEASE"));
	}

	/**
	 * Once, not five times. It has no tab of its own, so repeating it under every
	 * tab would turn one unknown page into five.
	 */
	@Test
	public void itIsNotRepeatedUnderEveryTab() throws Exception
	{
		for (String tab : new String[]{"Bosses", "Raids", "Clues", "Minigames"})
		{
			assertFalse("the unknown page was repeated under " + tab,
				has(logRows(tab), "NEW SINCE THIS RELEASE"));
		}
	}

	/** And a journal with nothing strange in it grows no such section. */
	@Test
	public void anOrdinaryJournalGrowsNoSuchSection() throws Exception
	{
		File dir = new File(System.getProperty("java.io.tmpdir"), "chronicle-newpages-plain");
		//noinspection ResultOfMethodCallIgnored
		dir.mkdirs();
		try (FileWriter w = new FileWriter(new File(dir, "somebody.json")))
		{
			w.write("{\"schema\":1,\"rsn\":\"Somebody\",\"drops\":{},"
				+ "\"collection_log\":{\"finished\":0,\"available\":1717,"
				+ "\"by_cat\":{\"Vorkath\":{}}},"
				+ "\"trackers\":{},\"skills\":{},\"feed\":[]}");
		}
		PanelPreviewTest.StubPlugin s = PanelPreviewTest.journalStub(dir.getPath(), "Somebody");
		final List<String> out = new ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				ChroniclePanel p = new ChroniclePanel(s);
				Field tf = ChroniclePanel.class.getDeclaredField("clogTab");
				tf.setAccessible(true);
				tf.set(p, "Other");
				Method m = ChroniclePanel.class.getDeclaredMethod("buildLog");
				m.setAccessible(true);
				List<Component> flat = new ArrayList<>();
				flatten((Component) m.invoke(p), flat);
				for (Component c : flat)
				{
					if (c instanceof JLabel && ((JLabel) c).getText() != null)
					{
						out.add(((JLabel) c).getText());
					}
				}
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		assertFalse("a known page was reported as new: " + out,
			has(out, "NEW SINCE THIS RELEASE"));
	}
}
