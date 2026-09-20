/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.awt.Component;
import java.awt.Container;
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
 * The journal shows the sitting the reader is in.
 *
 * <p>Everything else already reached it as it happened: a level, a pet, a log
 * slot, a drop. A SITTING did not. It is written as one dated line when it
 * closes, so the journal listed every sitting its owner had ever had except the
 * one they were in the middle of - the one they could watch going by.
 */
public class LiveSittingTest
{
	private static List<String> journal(JsonObject sitting) throws Exception
	{
		PanelPreviewTest.StubPlugin stub = PanelPreviewTest.fixtureStub();
		Field f = stub.getClass().getDeclaredField("liveSitting");
		f.setAccessible(true);
		f.set(stub, sitting);

		final List<String> out = new ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				javax.swing.UIManager.setLookAndFeel(
					new net.runelite.client.ui.laf.RuneLiteLAF());
				ChroniclePanel p = new ChroniclePanel(stub);
				Method m = ChroniclePanel.class.getDeclaredMethod("buildJournal");
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

	private static JsonObject sitting()
	{
		return new Gson().fromJson("{\"type\":\"SESSION\",\"live\":true,\"ts\":"
			+ System.currentTimeMillis() + ",\"data\":{\"minutes\":97,"
			+ "\"xp\":734000,\"drops\":41,\"dropsGp\":2300000,"
			+ "\"left\":3,\"leftGp\":1200,\"leftKills\":2}}", JsonObject.class);
	}

	@Test
	public void theSittingInProgressIsInTheJournal() throws Exception
	{
		List<String> said = journal(sitting());
		assertTrue("the sitting the reader is having is not in their journal: "
			+ said.subList(0, Math.min(12, said.size())), has(said, "1h 37m"));
	}

	/** And it reads as the line it will become, not as something else. */
	@Test
	public void itReadsAsAnOrdinarySessionLine() throws Exception
	{
		List<String> said = journal(sitting());
		assertTrue(said.toString(), has(said, "Session:"));
		assertTrue("it should carry what the sitting has taken", has(said, "41 drops"));
	}

	/** With no sitting there is nothing extra, and the journal is as it was. */
	@Test
	public void withNoSittingTheJournalIsUnchanged() throws Exception
	{
		List<String> with = journal(sitting());
		List<String> without = journal(null);
		assertTrue("the sitting should be the only difference",
			with.size() > without.size());
		assertFalse("a journal with no sitting in progress invented one",
			has(without, "1h 37m"));
	}
}
