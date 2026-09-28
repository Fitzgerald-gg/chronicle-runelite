/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.awt.Font;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CombatAchievementsTest
{
	static JsonObject table()
	{
		try (InputStreamReader r = new InputStreamReader(
			ChroniclePanel.class.getResourceAsStream(
				"/chronicle/osrs_combat_achievements.json"), StandardCharsets.UTF_8))
		{
			return new Gson().fromJson(r, JsonObject.class);
		}
		catch (Exception e)
		{
			throw new AssertionError("the table is not on the classpath", e);
		}
	}

	static JsonObject tasks()
	{
		return table().getAsJsonObject("tasks");
	}

	private static JsonObject meta()
	{
		return table().getAsJsonObject("_meta");
	}

	@Test
	public void everyTierHoldsTheNumberOfTasksTheWikiCounts()
	{
		JsonObject totals = meta().getAsJsonObject("totals");
		Map<String, Integer> got = new HashMap<>();
		for (String id : tasks().keySet())
		{
			got.merge(tasks().getAsJsonObject(id).get("tier").getAsString(), 1, Integer::sum);
		}
		for (String tier : new String[]{"easy", "medium", "hard", "elite", "master",
			"grandmaster"})
		{
			assertEquals(tier, totals.get(tier).getAsInt(),
				got.getOrDefault(tier, 0).intValue());
		}
		assertEquals("total", totals.get("tasks").getAsInt(), tasks().size());
	}

	@Test
	public void theTiersAddUpToTheGamesPointTotal()
	{
		JsonObject points = meta().getAsJsonObject("points");
		long sum = 0;
		for (String id : tasks().keySet())
		{
			sum += points.get(tasks().getAsJsonObject(id).get("tier").getAsString()).getAsLong();
		}
		assertEquals(meta().getAsJsonObject("totals").get("points").getAsLong(), sum);
	}

	@Test
	public void withNoWitnessedCompletionTheTableSuppliesTheTotal() throws Exception
	{
		long table = meta().getAsJsonObject("totals").get("points").getAsLong();
		assertTrue("the bundled table has no points total to fall back to", table > 0);

		PanelPreviewTest.StubPlugin stub = new PanelPreviewTest.StubPlugin(null);
		assertEquals("a journal that has seen nothing reads the table",
			table, standing(stub)[1]);

		JsonObject data = new JsonObject();
		data.addProperty("totalPossiblePoints", 2624);
		JsonObject seen = new JsonObject();
		seen.addProperty("type", "COMBAT_ACHIEVEMENT");
		seen.add("data", data);
		stub.feed.add(seen);
		assertEquals(2624, standing(stub)[1]);
	}

	private static long[] standing(PanelPreviewTest.StubPlugin stub) throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		final ChroniclePanel[] hold = new ChroniclePanel[1];
		javax.swing.SwingUtilities.invokeAndWait(() -> hold[0] = new ChroniclePanel(stub));
		java.lang.reflect.Method m = ChroniclePanel.class.getDeclaredMethod("combatStanding");
		m.setAccessible(true);
		return (long[]) m.invoke(hold[0]);
	}

	@Test
	public void everyCharacterCanActuallyBePainted() throws Exception
	{
		Font f = panelFont();
		for (String id : tasks().keySet())
		{
			JsonObject t = tasks().getAsJsonObject(id);
			for (String field : new String[]{"name", "task", "monster", "type"})
			{
				String s = t.get(field).getAsString();
				for (int cp : s.codePoints().toArray())
				{
					assertTrue("task " + id + " " + field + " carries U+"
							+ Integer.toHexString(cp) + " which the panel's font cannot draw: "
							+ s,
						cp < 0x20 || f.canDisplay(cp));
				}
			}
		}
	}

	@Test
	public void everyTaskIsKeyedByTheGamesOwnId()
	{
		Set<String> names = new HashSet<>();
		for (String id : tasks().keySet())
		{
			assertTrue("id " + id + " is not a number", id.matches("\\d+"));
			JsonObject t = tasks().getAsJsonObject(id);
			assertFalse("task " + id + " has no name", t.get("name").getAsString().isEmpty());
			assertFalse("task " + id + " says nothing to do",
				t.get("task").getAsString().isEmpty());
			assertTrue("two tasks are called " + t.get("name").getAsString(),
				names.add(t.get("name").getAsString()));
		}
	}

	@Test
	public void theMonsterColumnIsWiderThanTheBossRoster()
	{
		Set<String> roster = new HashSet<>();
		for (com.google.gson.JsonElement b : new Gson().fromJson(
			new InputStreamReader(ChroniclePanel.class.getResourceAsStream(
				"/chronicle/osrs_bosses.json"), StandardCharsets.UTF_8),
			com.google.gson.JsonArray.class))
		{
			roster.add(b.getAsJsonObject().get("name").getAsString());
		}
		Set<String> outside = new HashSet<>();
		for (String id : tasks().keySet())
		{
			String m = tasks().getAsJsonObject(id).get("monster").getAsString();
			if (!roster.contains(m))
			{
				outside.add(m);
			}
		}
		assertTrue("the roster now covers every CA monster, which would be a first: "
				+ outside,
			outside.size() > 20);
		for (String want : new String[]{"Aberrant Spectre", "Crystalline Hunllef",
			"Fortis Colosseum"})
		{
			assertTrue(want + " should be outside the roster", outside.contains(want));
		}
	}

	@Test
	public void namesKeepTheirOwnPunctuation()
	{
		assertNotNull("a task whose name is a question", byName("Defence? What Defence?"));
		JsonObject insanity = byName("Insanity");
		assertNotNull("the grandmaster task Cameron searched in game", insanity);
		assertEquals("grandmaster", insanity.get("tier").getAsString());
		assertEquals("Complete 'Perfect Wardens' at expert or above.",
			insanity.get("task").getAsString());
	}

	@Test
	public void aRequirementIsAWholeSentence()
	{
		for (String id : tasks().keySet())
		{
			String task = tasks().getAsJsonObject(id).get("task").getAsString();
			assertFalse("task " + id + " was cut off: " + task, task.endsWith("..."));
			assertTrue("task " + id + " is too short to be a requirement: " + task,
				task.length() > 5);
		}
	}

	private static JsonObject byName(String name)
	{
		for (String id : tasks().keySet())
		{
			JsonObject t = tasks().getAsJsonObject(id);
			if (name.equals(t.get("name").getAsString()))
			{
				return t;
			}
		}
		return null;
	}

	static Font panelFont() throws Exception
	{
		File ttf = new File(System.getProperty("java.io.tmpdir"), "chronicle-rs-small.ttf");
		if (!ttf.exists())
		{
			try (java.io.InputStream in = net.runelite.client.ui.FontManager.class
				.getResourceAsStream("runescape_small.ttf"))
			{
				assertNotNull("the pixel font is not on the classpath", in);
				java.nio.file.Files.copy(in, ttf.toPath(),
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		}
		return Font.createFont(Font.TRUETYPE_FONT, ttf);
	}
}
