/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.JsonObject;
import java.util.List;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FeedTest
{
	private final Harness h = new Harness().login();

	private String only(String type, String field)
	{
		List<JsonObject> rows = h.feed(type);
		assertEquals(type + " " + rows, 1, rows.size());
		return rows.get(0).get(field).getAsString();
	}

	@Test
	public void milestonesSaidInChatAreKept()
	{
		String[][] cases = {
			{"New item added to your collection log: Dragon pickaxe", "COLLECTION", "itemName", "Dragon pickaxe"},
			{"Congratulations, you've completed a Grandmaster combat task: Insanity.", "COMBAT_ACHIEVEMENT", "tier",
				"GRANDMASTER"},
			{"Congratulations, you've completed a Hard combat task: Peach Conjurer (3 points).", "COMBAT_ACHIEVEMENT",
				"task", "Peach Conjurer"},
			{"You have completed 87 hard Treasure Trails.", "CLUE", "clueCount", "87"},
			{"Congratulations! You have completed all of the hard tasks in the Varrock area.", "DIARY", "area",
				"Varrock"},
		};
		for (String[] c : cases)
		{
			Harness fresh = new Harness().login();
			fresh.chat(c[0]);
			List<JsonObject> rows = fresh.feed(c[1]);
			assertEquals(c[0], 1, rows.size());
			assertEquals(c[0], c[3], rows.get(0).get(c[2]).getAsString());
		}
	}

	@Test
	public void aDiarySaidInADialogueBoxCountsButNothingElseThere()
	{
		h.chat(ChatMessageType.MESBOX, "Congratulations! You have completed all of the elite tasks in the Karamja area.");
		h.chat(ChatMessageType.MESBOX, "Your Zulrah kill count is: 5.");
		h.chat(ChatMessageType.PUBLICCHAT, "New item added to your collection log: Dragon pickaxe");
		assertEquals("ELITE", only("DIARY", "difficulty"));
		assertTrue(h.kills().isEmpty());
		assertTrue(h.feed("COLLECTION").isEmpty());
	}

	@Test
	public void aPetIsNamedByTheLineAfterItAndAnUnnamedOneExpires()
	{
		h.chat("You have a funny feeling like you're being followed.");
		h.chat("New item added to your collection log: Baby mole");
		assertEquals("Baby mole", only("PET", "petName"));

		Harness other = new Harness().login();
		other.chat("You feel something weird sneaking into your backpack.").ticks(5);
		other.chat("Untradeable drop: Heron");
		assertTrue(other.feed("PET").isEmpty());
		other.chat("You feel something weird sneaking into your backpack.");
		other.chat("Untradeable drop: Heron");
		assertEquals("Heron", other.feed("PET").get(0).get("petName").getAsString());
	}

	@Test
	public void aDeathNamesWhatWasFightingUs()
	{
		h.attackedBy(h.npc("Vorkath", 8061, new WorldPoint(3200, 3205, 0), 7)).tick();
		h.dies(null);
		h.chat("Oh dear, you are dead!");
		assertEquals("Vorkath", only("DEATH", "killerName"));
		assertEquals(1, h.tracker("deaths"));
		h.dies(h.npc("Goblin", 3029, new WorldPoint(3201, 3201, 0), 1));
		assertEquals(1, h.feed("DEATH").size());
	}

	@Test
	public void aLevelGainedIsKeptOnceAndAFirstReadingIsNot()
	{
		h.level(Skill.ATTACK, 60, 273742).tick();
		assertTrue(h.feed("LEVEL").isEmpty());
		h.level(Skill.ATTACK, 61, 302288).level(Skill.ATTACK, 61, 302300).tick().tick();
		JsonObject lvl = h.feed("LEVEL").get(0);
		assertEquals(1, h.feed("LEVEL").size());
		assertEquals("Attack", lvl.get("skill").getAsString());
		assertEquals(61, lvl.get("level").getAsInt());
		h.level(Skill.ATTACK, 99, 13034431).tick().level(Skill.ATTACK, 100, 14391160).tick();
		assertEquals(2, h.feed("LEVEL").size());
	}

	@Test
	public void theAchievementSheetFollowsTheGame()
	{
		h.varbit(4479 + 2, 1).varbit(VarbitID.KARAMJA_EASY_COUNT, 60).varbit(VarbitID.KARAMJA_MED_COUNT, 1)
			.varbit(VarbitID.CA_POINTS, 150).varp(3116, 0b101)
			.quest(Quest.COOKS_ASSISTANT.getId(), "FINISHED");
		assertFalse(h.journal().getAsJsonObject("achievements").toString().contains("FINISHED"));
		JsonObject a = h.tick().journal().getAsJsonObject("achievements");
		assertTrue(a.getAsJsonObject("diaries").getAsJsonObject("varrock").get("hard").getAsBoolean());
		assertFalse(a.getAsJsonObject("diaries").getAsJsonObject("varrock").get("elite").getAsBoolean());
		assertTrue(a.getAsJsonObject("diaries").getAsJsonObject("karamja").get("easy").getAsBoolean());
		assertFalse(a.getAsJsonObject("diaries").getAsJsonObject("karamja").get("medium").getAsBoolean());
		assertEquals(150, a.getAsJsonObject("combat").get("points").getAsInt());
		assertEquals("[0,2]", a.getAsJsonObject("combat").get("tasksDone").toString());
		assertEquals("FINISHED", a.getAsJsonObject("quests").get(Quest.COOKS_ASSISTANT.getName()).getAsString());
		assertEquals("NOT_STARTED", a.getAsJsonObject("quests").get(Quest.DEMON_SLAYER.getName()).getAsString());
	}

	@Test
	public void aQuestScrollNamesTheQuest()
	{
		h.questScroll("<col=ff0000>Dragon Slayer I</col>");
		assertEquals("Dragon Slayer I", only("QUEST", "questName"));
	}
}
