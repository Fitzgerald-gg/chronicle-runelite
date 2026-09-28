package chronicle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PanelRebuildCostTest
{
	private static Field field(String name) throws Exception
	{
		Field f = ChroniclePanel.class.getDeclaredField(name);
		f.setAccessible(true);
		return f;
	}

	private static void edt() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
		});
	}

	private static ChroniclePanel panel() throws Exception
	{
		java.io.File dir = new java.io.File(System.getProperty("java.io.tmpdir"),
			"chronicle-rebuild-cost");
		dir.mkdirs();
		PanelPreviewTest.StubPlugin s = PanelPreviewTest.journalStub(dir.getPath(), "nobody");
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(s));
		return hold[0];
	}

	@Test
	public void asksInsideOneTickCoalesce() throws Exception
	{
		ChroniclePanel p = panel();
		Field builds = field("buildsRun");
		edt();
		long before = builds.getLong(p);

		p.update();
		p.update();
		p.update();
		edt();

		assertEquals("three asks in a tick drew three boards",
			1, builds.getLong(p) - before);
	}

	@Test
	public void aLaterAskStillEarnsItsOwnBoard() throws Exception
	{
		ChroniclePanel p = panel();
		Field builds = field("buildsRun");
		edt();
		long before = builds.getLong(p);

		p.update();
		edt();
		p.update();
		edt();

		assertEquals(2, builds.getLong(p) - before);
	}

	@Test
	public void aHiddenPanelIsNotDrawnAndIsOwedOneOnReturn() throws Exception
	{
		ChroniclePanel p = panel();
		Field builds = field("buildsRun");
		field("everShown").setBoolean(p, true);
		edt();
		long before = builds.getLong(p);

		for (int i = 0; i < 20; i++)
		{
			p.update();
			edt();
		}
		assertEquals("a board was drawn for a panel nobody was looking at",
			0, builds.getLong(p) - before);
		assertTrue("the panel does not know it owes the reader a board",
			field("staleWhileHidden").getBoolean(p));

		field("staleWhileHidden").setBoolean(p, false);
		field("everShown").setBoolean(p, false);
		p.update();
		edt();
		assertEquals("the reader came back to the board they left",
			1, builds.getLong(p) - before);
	}

	@Test
	public void theSittingsOwnTickerIsNotDrawnForAHiddenPanel() throws Exception
	{
		ChroniclePanel p = panel();
		Field builds = field("buildsRun");
		field("everShown").setBoolean(p, true);
		Field t = field("homeTicker");
		javax.swing.Timer ticker = (javax.swing.Timer) t.get(p);
		edt();
		long before = builds.getLong(p);

		for (int i = 0; i < 5; i++)
		{
			final int n = i;
			SwingUtilities.invokeAndWait(() ->
			{
				for (java.awt.event.ActionListener l : ticker.getActionListeners())
				{
					l.actionPerformed(new java.awt.event.ActionEvent(ticker, n, "tick"));
				}
			});
			edt();
		}
		assertEquals("the sitting redrew itself for a panel nobody was looking at",
			0, builds.getLong(p) - before);
		assertTrue("and did not even know it owed the reader a board",
			field("staleWhileHidden").getBoolean(p));
	}

	@Test
	public void theMemoDoesNotOutliveTheBuild() throws Exception
	{
		ChroniclePanel p = panel();
		Method rebuild = ChroniclePanel.class.getDeclaredMethod("rebuild");
		rebuild.setAccessible(true);
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				rebuild.invoke(p);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		assertEquals("the sources memo outlived its build",
			null, field("buildSources").get(p));
		assertEquals("the collection log memo outlived its build",
			null, field("buildClog").get(p));
	}
}
