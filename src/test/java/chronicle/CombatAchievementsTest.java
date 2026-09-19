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

/**
 * The bundled combat achievement table, held against things outside itself.
 *
 * <p>A generated OSRS table is wrong data a reader BELIEVES, and this one is the
 * worst kind to get wrong: a dryness rate slightly off is a curiosity, but a
 * requirement shown wrong sends a player to go and do the wrong thing. So every
 * trap found while building it is asserted here, and the counts are checked
 * against the wiki's own totals table rather than against the file itself.
 */
public class CombatAchievementsTest
{
	private static JsonObject table()
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

	private static JsonObject tasks()
	{
		return table().getAsJsonObject("tasks");
	}

	private static JsonObject meta()
	{
		return table().getAsJsonObject("_meta");
	}

	/**
	 * TRAP: a tier page that fails to render, or a template change, drops tasks
	 * silently and the file still looks healthy. The wiki states its own per-tier
	 * counts in a table the generator does not read, so they are an outside check.
	 */
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

	/** And the points those tasks are worth add up to what the wiki says they do. */
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

	/**
	 * TRAP: the panel paints in the RuneScape pixel font, which has no glyph for
	 * an en dash, a right single quote, or any of the arrows and bullets. A
	 * character it cannot draw paints .notdef, a hollow box, in the middle of a
	 * requirement. Every task name and description is checked against the font
	 * itself rather than against a list of characters someone remembered.
	 */
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

	/**
	 * TRAP: the wiki's task id is what makes a rename survivable. Two tasks
	 * sharing one, or a table keyed by name instead, and a renamed task either
	 * vanishes or overwrites another.
	 */
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

	/**
	 * TRAP: the monster column is NOT the boss roster, and a surface that keys on
	 * the roster silently drops a third of the tasks. CA names slayer monsters
	 * (Aberrant Spectre, Bloodveld), raid encounters (Crystalline Hunllef, Fortis
	 * Colosseum) and quest bosses (Galvek) that the hiscores have no node for.
	 */
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

	/**
	 * TRAP: punctuation in a task's own name. "Defence? What Defence?" is a real
	 * task, and a name carrying question marks, apostrophes or brackets is exactly
	 * what a careless parse or a careless search will mangle.
	 */
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

	/** A task the player can read start to finish, not a truncated fragment. */
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
