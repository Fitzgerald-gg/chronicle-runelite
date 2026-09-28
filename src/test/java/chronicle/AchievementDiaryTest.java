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

public class AchievementDiaryTest
{
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
					assertFalse("a footnote reference outlived its footnote: " + task,
						task.matches(".*\\[[a-z]{0,2}\\s?\\d*\\]$"));
				}
			}
		}
	}

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
