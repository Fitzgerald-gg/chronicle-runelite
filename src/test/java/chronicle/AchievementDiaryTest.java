/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.Font;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The bundled achievement diary table, held against things outside itself.
 *
 * <p>RuneLite core ships these as compiled per-region classes, but
 * DiaryRequirement is package private, so reading them means reflecting into
 * another plugin's internals. Bundled instead, and checked the same way the
 * combat achievements are: a requirement shown wrong sends somebody to go and do
 * the wrong thing.
 */
public class AchievementDiaryTest
{
	/** The twelve diaries the game has. Not read from the file it checks. */
	private static final String[] REGIONS = {
		"Ardougne", "Desert", "Falador", "Fremennik", "Kandarin", "Karamja",
		"Kourend & Kebos", "Lumbridge & Draynor", "Morytania", "Varrock",
		"Western Provinces", "Wilderness"};

	private static final String[] TIERS = {"easy", "medium", "hard", "elite"};

	private static JsonObject diaries()
	{
		try (InputStreamReader r = new InputStreamReader(
			ChroniclePanel.class.getResourceAsStream(
				"/chronicle/osrs_achievement_diaries.json"), StandardCharsets.UTF_8))
		{
			return new Gson().fromJson(r, JsonObject.class).getAsJsonObject("diaries");
		}
		catch (Exception e)
		{
			throw new AssertionError("the table is not on the classpath", e);
		}
	}

	/** TRAP: a region silently missing, which is what a page rename looks like. */
	@Test
	public void everyDiaryIsHere()
	{
		JsonObject d = diaries();
		for (String region : REGIONS)
		{
			assertTrue("no " + region + " diary", d.has(region));
		}
		assertEquals("a thirteenth diary appeared: " + d.keySet(),
			REGIONS.length, d.size());
	}

	/**
	 * TRAP: the one that actually bit. Scanning forward from a tier HEADING finds
	 * whatever table comes first, and on Karamja that is the audio player, which
	 * cost the region 39 of its 44 tasks with the file still looking healthy.
	 * Ardougne was quietly ten short the same way. No tier is thin now.
	 */
	@Test
	public void noTierIsSuspiciouslyThin()
	{
		JsonObject d = diaries();
		for (String region : REGIONS)
		{
			JsonObject r = d.getAsJsonObject(region);
			int whole = 0;
			for (String tier : TIERS)
			{
				assertTrue(region + " has no " + tier + " tier", r.has(tier));
				int n = r.getAsJsonArray(tier).size();
				assertTrue(region + "/" + tier + " holds only " + n + " tasks, which is"
					+ " what a wrong table looks like", n >= 5);
				whole += n;
			}
			assertTrue(region + " holds only " + whole + " tasks in total",
				whole >= 30);
		}
	}

	/**
	 * TRAP: the panel paints in the RuneScape pixel font. A bullet, an en dash or
	 * a curly quote paints .notdef, a hollow box, in the middle of a requirement.
	 * The Fremennik Blast Furnace task really does carry bullets between its
	 * dialogue options, and they are mapped to a character the font has.
	 */
	@Test
	public void everyCharacterCanActuallyBePainted() throws Exception
	{
		Font f = CombatAchievementsTest.panelFont();
		JsonObject d = diaries();
		for (String region : REGIONS)
		{
			JsonObject r = d.getAsJsonObject(region);
			for (String tier : TIERS)
			{
				JsonArray tasks = r.getAsJsonArray(tier);
				for (int i = 0; i < tasks.size(); i++)
				{
					JsonObject t = tasks.get(i).getAsJsonObject();
					for (String field : new String[]{"task", "requirements"})
					{
						String s = t.get(field).getAsString();
						for (int cp : s.codePoints().toArray())
						{
							assertTrue(region + "/" + tier + "[" + i + "] " + field
									+ " carries U+" + Integer.toHexString(cp)
									+ " which the panel cannot draw: " + s,
								cp < 0x20 || f.canDisplay(cp));
						}
					}
				}
			}
		}
	}

	/** TRAP: the wiki's numbering leaking in, or a task cut off mid-sentence. */
	@Test
	public void aTaskIsAWholeInstructionAndCarriesNoListNumber()
	{
		JsonObject d = diaries();
		for (String region : REGIONS)
		{
			JsonObject r = d.getAsJsonObject(region);
			for (String tier : TIERS)
			{
				JsonArray tasks = r.getAsJsonArray(tier);
				for (int i = 0; i < tasks.size(); i++)
				{
					String task = tasks.get(i).getAsJsonObject().get("task").getAsString();
					assertFalse(region + "/" + tier + "[" + i + "] kept the wiki's own"
							+ " list number: " + task,
						task.matches("^\\d+\\..*"));
					assertTrue("too short to be an instruction: " + task,
						task.length() > 8);
					// NOT endsWith("...") : "Between a Rock..." is a real quest and
					// three Desert tasks name it. A cell cut short by the parser ends
					// without punctuation at all, which is the thing worth catching.
					assertFalse("a footnote reference outlived its footnote: " + task,
						task.matches(".*\\[[a-z]{0,2}\\s?\\d*\\]$"));
				}
			}
		}
	}

	/** A requirement is always stated, even when the answer is that there is none. */
	@Test
	public void everyTaskSaysWhatItNeeds()
	{
		JsonObject d = diaries();
		Set<String> seen = new HashSet<>();
		for (String region : REGIONS)
		{
			JsonObject r = d.getAsJsonObject(region);
			for (String tier : TIERS)
			{
				JsonArray tasks = r.getAsJsonArray(tier);
				for (int i = 0; i < tasks.size(); i++)
				{
					JsonObject t = tasks.get(i).getAsJsonObject();
					assertNotNull(t.get("requirements"));
					seen.add(t.get("requirements").getAsString());
				}
			}
		}
		assertTrue("every task claiming the same requirement is a parse that failed",
			seen.size() > 100);
		assertTrue("'None' is a real answer and should appear", seen.contains("None"));
	}

	/** Known tasks, so a reshaped page cannot quietly hand back different text. */
	@Test
	public void theTasksSayWhatTheGameSaysTheySay()
	{
		JsonObject d = diaries();
		assertEquals("Pick 5 bananas from the plantation located east of the volcano.",
			d.getAsJsonObject("Karamja").getAsJsonArray("easy").get(0)
				.getAsJsonObject().get("task").getAsString());
		assertTrue(d.getAsJsonObject("Ardougne").getAsJsonArray("easy").get(0)
			.getAsJsonObject().get("task").getAsString()
			.startsWith("Have Wizard Cromperty teleport you to the Rune Essence mine."));
	}

	/**
	 * TRAP: a wiki note welded to the word before it. The wiki puts "[sic]" and
	 * the "[not boostable]" that says a level cannot be potioned into a &lt;sup&gt;,
	 * and stripping tags without putting the markup's space back produced
	 * "70 Defence[not boostable]", which a reader takes for a typo in the plugin
	 * rather than for a note about the requirement. Cheap to reintroduce, since
	 * the tag stripper deliberately joins everything else with no space.
	 */
	@Test
	public void noNoteIsWeldedToTheWordBeforeIt()
	{
		java.util.regex.Pattern glued = java.util.regex.Pattern.compile("\\S\\[");
		List<String> bad = new ArrayList<>();
		JsonObject all = diaries();
		for (String region : all.keySet())
		{
			JsonObject tiers = all.getAsJsonObject(region);
			for (String tier : TIERS)
			{
				if (!tiers.has(tier))
				{
					continue;
				}
				for (JsonElement e : tiers.getAsJsonArray(tier))
				{
					JsonObject t = e.getAsJsonObject();
					for (String field : new String[]{"task", "requirements"})
					{
						if (t.has(field) && glued.matcher(t.get(field).getAsString()).find())
						{
							bad.add(region + " " + tier + " " + field + ": "
								+ t.get(field).getAsString());
						}
					}
				}
			}
		}
		assertTrue("a note is welded to the preceding word: " + bad, bad.isEmpty());
	}
}
