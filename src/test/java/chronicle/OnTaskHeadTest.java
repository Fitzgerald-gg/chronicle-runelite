package chronicle;

import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What the on-task card counts.
 *
 * <p>It used to say "Distinct items", which the list underneath already says
 * sixteen times over. What it could not say was the one thing that makes the
 * rest of the card mean anything: how many TASKS the take came off. Eighty one
 * million gp is a different sentence over ninety three tasks than over four.
 *
 * <p>And the whole card used to vanish the moment a reader picked one task,
 * because the figures were every task in the window and would have been a lie
 * under one task's name. They are narrowed now, so they can stay.
 */
public class OnTaskHeadTest
{
	private static final String JOURNAL =
		"{\"slayer\":{\"tasks\":["
		+ "{\"task\":\"Nechryael\",\"ts\":1700000000,\"kills\":120,"
		+ "\"monsters\":{\"Nechryael\":118,\"Nechryarch\":2},"
		+ "\"items\":{\"Death rune\":{\"id\":560,\"name\":\"Death rune\","
		+ "\"qty\":300,\"value\":60000}}},"
		+ "{\"task\":\"Nechryael\",\"ts\":1700100000,\"kills\":80,"
		+ "\"monsters\":{\"Nechryael\":80},"
		+ "\"items\":{\"Death rune\":{\"id\":560,\"name\":\"Death rune\","
		+ "\"qty\":100,\"value\":20000}}},"
		+ "{\"task\":\"Abyssal demons\",\"ts\":1700200000,\"kills\":200,"
		+ "\"monsters\":{\"Abyssal demon\":200},"
		+ "\"items\":{\"Abyssal whip\":{\"id\":4151,\"name\":\"Abyssal whip\","
		+ "\"qty\":1,\"value\":1500000}}}"
		+ "]}}";

	private static ChroniclePanel panel() throws Exception
	{
		File dir = new File(System.getProperty("java.io.tmpdir"), "chronicle-on-task-head");
		dir.mkdirs();
		try (FileWriter w = new FileWriter(new File(dir, "tasky.json")))
		{
			w.write(JOURNAL);
		}
		PanelPreviewTest.StubPlugin s = PanelPreviewTest.journalStub(dir.getPath(), "tasky");
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

	private static List<String> board(ChroniclePanel p) throws Exception
	{
		final List<String> said = new ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Method m = ChroniclePanel.class.getDeclaredMethod(
					"addOnTaskLoot", JPanel.class);
				m.setAccessible(true);
				JPanel host = new JPanel();
				host.setLayout(new BoxLayout(host, BoxLayout.Y_AXIS));
				List<Component> flat = new ArrayList<>();
				flatten((Component) m.invoke(p, host), flat);
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

	/** The row after a label, which is how every card line is built. */
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

	@Test
	public void theCardCountsTasksRatherThanDistinctItems() throws Exception
	{
		ChroniclePanel p = panel();
		set(p, "histGranularity", "Lifetime");
		List<String> said = board(p);
		org.junit.Assert.assertEquals("three tasks in the journal", "3", after(said, "Tasks"));
		assertFalse("the card still counts distinct items",
			said.contains("Distinct items"));
		org.junit.Assert.assertEquals("400 kills logged", "400", after(said, "Kills logged"));
		org.junit.Assert.assertEquals("one nechryarch", "2", after(said, "Superiors"));
	}

	/**
	 * Narrowed to one task, every figure is that task's. Two Nechryael
	 * assignments, two hundred kills, one superior pair.
	 */
	@Test
	public void oneTaskCountsOnlyItsOwn() throws Exception
	{
		ChroniclePanel p = panel();
		set(p, "histGranularity", "Lifetime");
		set(p, "lootTask", "Nechryael");
		List<String> said = board(p);
		assertTrue("the card went away when a task was picked", said.contains("Tasks"));
		org.junit.Assert.assertEquals("two Nechryael assignments", "2", after(said, "Tasks"));
		org.junit.Assert.assertEquals("only Nechryael kills", "200", after(said, "Kills logged"));
		org.junit.Assert.assertEquals("only Nechryael superiors", "2", after(said, "Superiors"));
	}

	/** The picture says the same thing the board does. */
	@Test
	public void thePictureCarriesTheSameCard() throws Exception
	{
		ChroniclePanel p = panel();
		set(p, "histGranularity", "Lifetime");
		set(p, "lootTask", "Abyssal demons");
		List<String> said = new ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Method tally = ChroniclePlugin.class.getDeclaredMethod(
					"onTaskTally", long.class, long.class, String.class, boolean.class);
				tally.setAccessible(true);
				Field pf = ChroniclePanel.class.getDeclaredField("plugin");
				pf.setAccessible(true);
				long[] t = (long[]) tally.invoke(pf.get(p),
					Long.MIN_VALUE / 2, Long.MAX_VALUE / 2, "Abyssal demons", true);
				Method m = ChroniclePanel.class.getDeclaredMethod("kindsPicture",
					List.class, long.class, long.class, long[].class);
				m.setAccessible(true);
				Field sf = ChroniclePanel.class.getDeclaredField("plugin");
				sf.setAccessible(true);
				ChroniclePlugin plug = (ChroniclePlugin) sf.get(p);
				List<LocalStore.BagItem> bag = plug.onTaskLoot(
					Long.MIN_VALUE / 2, Long.MAX_VALUE / 2, "Abyssal demons", true);
				List<Component> flat = new ArrayList<>();
				flatten((Component) m.invoke(p, bag, 1L, 1500000L, t), flat);
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
		org.junit.Assert.assertEquals("the shared picture lost the task count",
			"1", after(said, "Tasks"));
		assertFalse("the picture still counts distinct items",
			said.contains("Distinct items"));
	}
}
