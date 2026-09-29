/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class KillCountTest
{
	private final Harness h = new Harness().login();

	private Long kc(String name)
	{
		return h.kills().get(name);
	}

	private JsonObject lastSpine()
	{
		List<String> lines = h.spineLines();
		return new com.google.gson.Gson().fromJson(lines.get(lines.size() - 1), JsonObject.class);
	}

	@Test
	public void everyShapeOfTheChatCountIsBankedWithoutLoot()
	{
		String[][] cases = {
			{"Your Zulrah kill count is: 501.", "Zulrah", "501"},
			{"Your completed Theatre of Blood: Hard Mode count is: 40.", "Theatre of Blood: Hard Mode", "40"},
			{"Your Gauntlet completion count is: 32.", "Gauntlet", "32"},
			{"Your Barrows chest count is: 512.", "Barrows", "512"},
			{"Your Yama success count is: 10.", "Yama", "10"},
		};
		for (String[] c : cases)
		{
			h.chat(c[0]);
		}
		Map<String, Long> kills = h.kills();
		for (String[] c : cases)
		{
			assertEquals(c[1] + " in " + kills, Long.valueOf(c[2]), kills.get(c[1]));
		}
		assertEquals(5, h.journal().getAsJsonObject("chat_kcs").size());
	}

	@Test
	public void aChatCountFindsThePageItBelongsTo()
	{
		h.clogPage("Wintertodt", Arrays.asList("Rewards claimed: 1,078", "Wintertodt kills: 447")).save();
		h.chat("Your subdued Wintertodt count is: 448.");
		assertEquals(Long.valueOf(448), kc("Wintertodt"));
	}

	@Test
	public void aLapAHarvestAndAnOlderReadingAreNotKills()
	{
		h.chat("Your Ape Atoll Agility lap count is: 1337.");
		h.chat("Your Herbiboar harvest count is: 1169.");
		assertTrue(h.kills().toString(), h.kills().isEmpty());
		h.chat("Your Zulrah kill count is: 600.");
		h.chat("Your Zulrah kill count is: 501.");
		assertEquals(Long.valueOf(600), kc("Zulrah"));
	}

	@Test
	public void theChatCountSurvivesAReload()
	{
		h.chat("Your Zulrah kill count is: 501.");
		h.logout().login();
		assertEquals(Long.valueOf(501), kc("Zulrah"));
	}

	@Test
	public void theKillLogIsReadForEverySpecies()
	{
		h.killLog("Kurask", "1,624", "Abyssal demons", "300").save();
		assertEquals(Long.valueOf(1624), kc("Kurask"));
		assertEquals(Long.valueOf(300), kc("Abyssal demons"));
	}

	@Test
	public void aLogPageCountsItsLabelledKillLineAndNothingElse()
	{
		h.clogPage("Wintertodt", Arrays.asList("Obtained: 5/10", "Rewards claimed: 1,078", "Wintertodt kills: 447"));
		h.clogPage("Tempoross", Arrays.asList("Personal Best: 3:46", "Reward permits claimed: 1,048",
			"Tempoross kills: 455"));
		h.clogPage("Abyssal Sire", Arrays.asList());
		h.save();
		assertEquals(Long.valueOf(447), kc("Wintertodt"));
		assertEquals(Long.valueOf(455), kc("Tempoross"));
		assertNull(kc("Abyssal Sire"));
	}

	@Test
	public void aStatedCountKeepsMovingWithEachKillAndTheChatBoxOutranksTheLog()
	{
		h.kill("Abyssal demon", 415, 526, 1).tick();
		h.killLog("Abyssal demons", "2,523").save();
		for (int i = 0; i < 4; i++)
		{
			h.kill("Abyssal demon", 415, 526, 1).tick();
		}
		assertEquals(Long.valueOf(2527), kc("Abyssal demons"));
		assertNull(kc("Abyssal demon"));

		h.killLog("Zulrah", "600").save();
		h.chat("Your Zulrah kill count is: 501.");
		assertEquals(Long.valueOf(501), kc("Zulrah"));
		h.killLog("Zulrah", "600").save();
		assertEquals(Long.valueOf(501), kc("Zulrah"));
	}

	@Test
	public void killsTheLedgerSawFloorTheCount()
	{
		for (int i = 0; i < 3; i++)
		{
			h.kill("Zalcano", 9049, 526, 1).tick();
		}
		assertEquals(Long.valueOf(3), kc("Zalcano"));
		h.chat("Your Zalcano kill count is: 2,023.");
		assertEquals(Long.valueOf(2023), kc("Zalcano"));
	}

	@Test
	public void aFirstReadingIsACorrectionAndLaterOnesArePlay()
	{
		for (int i = 0; i < 3; i++)
		{
			h.kill("Kurask", 410, 526, 1).tick();
		}
		h.killLog("Kurask", "1,624").save().tick();
		JsonObject line = lastSpine();
		assertEquals(1624, line.getAsJsonObject("kcs").get("Kurask").getAsLong());
		assertEquals(1621, line.getAsJsonObject("adj").getAsJsonObject("kcs").get("Kurask").getAsLong());
		assertEquals(1621, line.getAsJsonObject("adj").getAsJsonObject("counters").get("kills").getAsLong());

		h.kill("Kurask", 410, 526, 1).tick().kill("Kurask", 410, 526, 1).tick();
		h.killLog("Kurask", "1,626").save().tick();
		JsonObject after = lastSpine();
		assertEquals(1621, after.getAsJsonObject("adj").getAsJsonObject("kcs").get("Kurask").getAsLong());
		assertEquals(Long.valueOf(1626), kc("Kurask"));
	}

	@Test
	public void aFallIsACorrectionToo()
	{
		h.clogPage("Wintertodt", Arrays.asList("Rewards claimed: 1,078")).save().tick();
		h.clogPage("Wintertodt", Arrays.asList("Rewards claimed: 1,078", "Wintertodt kills: 447")).save().tick();
		JsonObject adj = lastSpine().getAsJsonObject("adj");
		assertEquals(-631, adj.getAsJsonObject("kcs").get("Wintertodt").getAsLong());
	}

	@Test
	public void aChatLinesFirstReadingKeepsTheKillItCameWith()
	{
		h.kill("Vorkath", 8061, 536, 1).tick();
		h.chat("Your Vorkath kill count is: 157.");
		h.tick();
		assertEquals(155, lastSpine().getAsJsonObject("adj").getAsJsonObject("kcs").get("Vorkath").getAsLong());
	}

	@Test
	public void aJournalFromAnOlderReckoningGetsItsStepOnLoad()
	{
		h.logout();
		Harness.write(h.file(), "{\"schema\":1,\"rsn\":\"Tester\",\"drops\":{\"Wintertodt\":{\"kc\":0,"
			+ "\"loots\":15,\"value\":0,\"items\":{}}},\"collection_log\":"
			+ "{\"kcs\":{\"Wintertodt\":50},\"slayer_kcs\":{\"Wintertodt\":20}},"
			+ "\"trackers\":{},\"skills\":{},\"feed\":[]}");
		Harness.write(h.spineFile(), "{\"date\":\"2026-09-20\",\"kcs\":{\"Wintertodt\":50},\"counters\":{\"kills\":50}}\n");
		h.login().tick();
		JsonObject adj = lastSpine().getAsJsonObject("adj");
		assertEquals(-30, adj.getAsJsonObject("kcs").get("Wintertodt").getAsLong());
		assertEquals(Long.valueOf(20), kc("Wintertodt"));
	}
}
