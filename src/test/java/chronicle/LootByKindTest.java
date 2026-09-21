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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The ledger read by what a thing IS rather than by what dropped it.
 *
 * <p>Every assertion here is a number or a control a reader would believe: a
 * head that totals the wrong bag, a count of items called a count of kinds, a
 * lens that disappears when the period changes. None of them throw.
 */
public class LootByKindTest
{
	private static final String JOURNAL =
		"{\"drops\":{"
		+ "\"Abyssal demon\":{\"kc\":100,\"value\":300,\"items\":{"
		+ "\"1\":{\"id\":1,\"name\":\"Fire rune\",\"qty\":400,\"value\":200},"
		+ "\"2\":{\"id\":2,\"name\":\"Death rune\",\"qty\":50,\"value\":100},"
		+ "\"3\":{\"id\":3,\"name\":\"Rune scimitar\",\"qty\":2,\"value\":30000}"
		+ "}}}}";

	private static ChroniclePanel panel() throws Exception
	{
		File dir = new File(System.getProperty("java.io.tmpdir"), "chronicle-by-kind");
		dir.mkdirs();
		try (FileWriter w = new FileWriter(new File(dir, "kindly.json")))
		{
			w.write(JOURNAL);
		}
		PanelPreviewTest.StubPlugin s = PanelPreviewTest.journalStub(dir.getPath(), "kindly");
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(s));
		return hold[0];
	}

	private static void set(ChroniclePanel p, String name, Object v) throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(p, v);
	}

	/** The Drops board as it would be drawn, with the fields the test set. */
	private static List<String> board(ChroniclePanel p) throws Exception
	{
		final List<String> said = new ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Method m = ChroniclePanel.class.getDeclaredMethod("buildDrops");
				m.setAccessible(true);
				List<Component> flat = new ArrayList<>();
				flatten((Component) m.invoke(p), flat);
				for (Component c : flat)
				{
					if (c instanceof JLabel && ((JLabel) c).getText() != null)
					{
						said.add(((JLabel) c).getText());
					}
				}
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		return said;
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

	private static boolean says(List<String> said, String what)
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

	/**
	 * The lens is a control on the board, not a control on one period.
	 *
	 * <p>Including the sitting, which briefly had no axis because the dated roll
	 * keeps one entry a day and so could not be asked what a few hours took. The
	 * sitting keeps its own entry now, in the same shape, so it has two readings
	 * like every other period and is offered the control that swaps them.
	 */
	@Test
	public void theKindLensIsOfferedAtEveryPeriod() throws Exception
	{
		ChroniclePanel p = panel();
		for (String period : ChroniclePanel.PERIODS)
		{
			set(p, "histGranularity", period);
			List<String> said = board(p);
			// One toggle carrying the reading it is on, so only one of the two
			// labels is ever drawn. The rule is that the axis is OFFERED here.
			assertTrue("the grouping control vanished at " + period + ": " + said,
				says(said, "By kind") || says(said, "By source"));
		}
	}

	/** Left behind is a list of items already; there is nothing to regroup. */
	@Test
	public void theLensIsNotOfferedOnWhatWasLeft() throws Exception
	{
		ChroniclePanel p = panel();
		set(p, "histGranularity", "Lifetime");
		set(p, "dropsLeftBehind", true);
		assertFalse("a grouping control was offered over left-behind items",
			says(board(p), "By kind"));
	}

	/** A drilled kind totals THAT kind. The bag's own totals are a lie under it. */
	@Test
	public void aDrilledKindTotalsItself() throws Exception
	{
		ChroniclePanel p = panel();
		set(p, "histGranularity", "Lifetime");
		set(p, "dropsByKind", true);
		set(p, "lootKind", "Runes");
		List<String> said = board(p);
		assertTrue("the head is not the kind's", says(said, "Runes"));
		// 400 fire + 50 death = 450 items, worth 300. The bag is 452 items
		// worth 30,300, and that is what the head used to say.
		assertTrue("the head did not total the kind", says(said, "450"));
		assertFalse("the head totalled the whole bag under the kind's name",
			says(said, "452"));
		assertTrue("two runes are two distinct items", says(said, "2 items"));
	}

	/** A kind the bag holds none of says so rather than drawing nothing. */
	@Test
	public void anEmptyKindSaysSo() throws Exception
	{
		ChroniclePanel p = panel();
		set(p, "histGranularity", "Lifetime");
		set(p, "dropsByKind", true);
		set(p, "lootKind", "Herbs");
		List<String> said = board(p);
		assertTrue("an empty kind drew a blank board",
			says(said, "Nothing of this kind"));
		assertTrue("there was no way back out of it", says(said, "All kinds"));
	}

	/** The summary counts distinct items, and does not call them kinds. */
	@Test
	public void theSummaryCallsItemsItems() throws Exception
	{
		ChroniclePanel p = panel();
		set(p, "histGranularity", "Lifetime");
		set(p, "dropsByKind", true);
		List<String> said = board(p);
		assertTrue("the kinds are not listed", says(said, "Runes"));
		assertTrue("the kinds are not listed", says(said, "Weapons"));
		assertFalse("a count of items was called a count of kinds",
			says(said, "kinds of thing"));
	}

	/** Reading by kind is a lens, and survives a look at another tab. */
	@Test
	public void theLensSurvivesATabMove() throws Exception
	{
		ChroniclePanel p = panel();
		set(p, "dropsByKind", true);
		Field f = ChroniclePanel.class.getDeclaredField("dropsByKind");
		f.setAccessible(true);
		Method common = null;
		int overloads = 0;
		for (Method m : ChroniclePanel.class.getDeclaredMethods())
		{
			if (m.getName().equals("applyCommon"))
			{
				common = m;
				overloads++;
			}
		}
		assertEquals("applyCommon is gone or has a second overload", 1, overloads);
		final Method call = common;
		call.setAccessible(true);
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				call.invoke(p, callArgs(call));
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		assertTrue("a tab move put the reader back on sources", f.getBoolean(p));
	}

	/** Whatever applyCommon takes, called with nothing in particular. */
	private static Object[] callArgs(Method m) throws Exception
	{
		Object[] args = new Object[m.getParameterCount()];
		for (int i = 0; i < args.length; i++)
		{
			Class<?> t = m.getParameterTypes()[i];
			args[i] = t.isEnum() ? t.getEnumConstants()[0] : null;
		}
		return args;
	}
}
