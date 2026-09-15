package chronicle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * What a rebuild costs, and how often one is paid for.
 *
 * <p>The panel is asked to redraw far more often than the record changes: a
 * region load, a world hop and a push can all ask inside one tick, and the
 * sidebar keeps asking while the reader is looking at another plugin entirely.
 * These are the two guards that stop the same board being drawn for nobody.
 */
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

	/**
	 * Three asks inside one tick are one board. The queued rebuild has not run
	 * yet, so it reads everything the later asks would have read.
	 */
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

	/** A rebuild that ran and then was asked for again is a second board. */
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

	/**
	 * Nothing is drawn for a panel the reader cannot see, and the moment they
	 * come back it is drawn once, however many pushes landed meanwhile.
	 */
	@Test
	public void aHiddenPanelIsNotDrawnAndIsOwedOneOnReturn() throws Exception
	{
		ChroniclePanel p = panel();
		Field builds = field("buildsRun");
		// The client has shown the panel at least once; until it has, isShowing
		// says nothing and every ask is honoured.
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

		// coming back: the hierarchy listener cannot fire headless, so the
		// return is played by hand exactly as it plays it
		field("staleWhileHidden").setBoolean(p, false);
		field("everShown").setBoolean(p, false);
		p.update();
		edt();
		assertEquals("the reader came back to the board they left",
			1, builds.getLong(p) - before);
	}

	/** The per-build memo is dropped at the top of each build, not kept. */
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
