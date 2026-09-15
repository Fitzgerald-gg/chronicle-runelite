package chronicle;

import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The counts-of-the-record page: what the journal holds, rather than what the
 * account has done.
 *
 * <p>It carries no date and no name anywhere, which is the point. It is the
 * page somebody hands over when they are being helped, so handing it over must
 * not hand over the account with it.
 */
public class InfoPageTest
{
	private static final String JOURNAL =
		"{\"schema\":1,\"rsn\":\"Somebody\",\"first_seen\":1600000000000,"
		+ "\"drops\":{"
		+ "\"Jelly\":{\"kc\":141,\"loots\":252,\"value\":1000,\"first_seen\":1600000000000,"
		+ "\"items\":{\"1\":{\"id\":1,\"name\":\"Chaos rune\",\"qty\":10,\"value\":600},"
		+ "\"2\":{\"id\":2,\"name\":\"Coins\",\"qty\":400,\"value\":400}}},"
		+ "\"Zulrah\":{\"kc\":7,\"loots\":9,\"value\":900,"
		+ "\"items\":{\"3\":{\"id\":3,\"name\":\"Zulrah's scales\",\"qty\":90,\"value\":900}}}"
		+ "},"
		+ "\"collection_log\":{\"finished\":214,\"available\":1717,"
		+ "\"slayer_kcs\":{\"Jellies\":979},\"kcs\":{\"Zulrah\":7}},"
		+ "\"slayer\":{\"completed\":2,\"tasks\":["
		+ "{\"task\":\"Jellies\",\"ts\":1700000000,\"kills\":10,\"monsters\":{\"Jelly\":10},"
		+ "\"items\":{\"Chaos rune\":{\"id\":1,\"qty\":5,\"value\":300}}}"
		+ "]},"
		+ "\"trackers\":{\"a\":1,\"b\":2,\"c\":3},\"skills\":{\"attack\":100},"
		+ "\"feed\":[{\"k\":1},{\"k\":2}],\"loot_days\":{\"2026-09-15\":{}}}";

	private static ChroniclePanel panel() throws Exception
	{
		File dir = new File(System.getProperty("java.io.tmpdir"), "chronicle-info-page");
		dir.mkdirs();
		try (FileWriter w = new FileWriter(new File(dir, "somebody.json")))
		{
			w.write(JOURNAL);
		}
		PanelPreviewTest.StubPlugin s = PanelPreviewTest.journalStub(dir.getPath(), "Somebody");
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(s));
		return hold[0];
	}

	private static List<String> say(ChroniclePanel p, String method, Object... args)
		throws Exception
	{
		final List<String> out = new ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Method m = args.length == 0
					? ChroniclePanel.class.getDeclaredMethod(method)
					: ChroniclePanel.class.getDeclaredMethod(method, String.class);
				m.setAccessible(true);
				List<Component> flat = new ArrayList<>();
				flatten((Component) (args.length == 0 ? m.invoke(p) : m.invoke(p, args)), flat);
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

	private static String after(List<String> said, String label)
	{
		for (int i = 0; i < said.size() - 1; i++)
		{
			if (label.equals(said.get(i)))
			{
				return said.get(i + 1);
			}
		}
		return null;
	}

	private static boolean has(List<String> said, String what)
	{
		for (String s : said)
		{
			if (s.toLowerCase(java.util.Locale.ROOT)
				.contains(what.toLowerCase(java.util.Locale.ROOT)))
			{
				return true;
			}
		}
		return false;
	}

	/** Found by typing, from the first letter, because it has no tab. */
	@Test
	public void itIsFoundWhileBeingTyped() throws Exception
	{
		ChroniclePanel p = panel();
		for (String q : new String[]{"i", "in", "inf", "info"})
		{
			assertTrue("typing \"" + q + "\" did not offer it",
				has(say(p, "buildSearch", q), "what the journal holds"));
		}
		assertFalse("it was offered for something it does not spell",
			has(say(p, "buildSearch", "zzz"), "what the journal holds"));
	}

	/** Every figure is a count of the record, and they are the record's. */
	@Test
	public void itCountsWhatTheJournalHolds() throws Exception
	{
		ChroniclePanel p = panel();
		List<String> said = say(p, "buildInfo");
		assertEquals("2", after(said, "Sources"));
		assertEquals("3 item rows across two sources", "3", after(said, "Item rows"));
		assertEquals("252 + 9", "261", after(said, "Loot events"));
		assertEquals("1", after(said, "Dated days"));
		assertEquals("1", after(said, "Assignments"));
		assertEquals("2", after(said, "Closed"));
		assertEquals("214 of 1,717", after(said, "Slots filled"));
		assertEquals("1", after(said, "Kill Log lines"));
		assertEquals("3", after(said, "Trackers"));
		// not asserted: the store normalises the feed on load, so a fixture's
		// raw entry count is not what the page will report
		assertEquals("1", after(said, "Schema"));
	}

	/**
	 * Nothing on it names the account or dates it. The journal holds an rsn and
	 * a first_seen, and neither reaches this page.
	 */
	@Test
	public void itHandsOverNoAccount() throws Exception
	{
		ChroniclePanel p = panel();
		List<String> said = say(p, "buildInfo");
		assertFalse("the page names the account", has(said, "Somebody"));
		assertFalse(has(said, "Tracked since"));
		assertFalse(has(said, "2020"));
		assertFalse(has(said, "Sept"));
	}
}
