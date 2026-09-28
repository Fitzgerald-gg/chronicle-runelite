/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class KillSourcePetChaseTest
{
	private static final List<String> PETS = Arrays.asList(
		"Abyssal protector", "Chompy chick", "Bloodhound", "Pet penance queen",
		"Lil' creator", "Quetzin", "Yami", "Gull", "Beef", "Aggy",
		"Dom", "Maggot marquess", "Mr McGroot", "Smolcano", "Tiny tempor");

	private final Map<String, Long> counters = new HashMap<>();
	private final Map<String, long[]> skills = new HashMap<>();
	private final JsonObject clog = new JsonObject();
	private final List<LocalStore.SourceRow> ledger = new ArrayList<>();
	private final JsonObject achievements = new JsonObject();

	private void kc(String page, long n)
	{
		JsonObject kcs = clog.has("kcs") ? clog.getAsJsonObject("kcs") : new JsonObject();
		kcs.addProperty(page, n);
		clog.add("kcs", kcs);
	}

	private void ledger(String source, int n)
	{
		ledger.add(new LocalStore.SourceRow(source, n, n, 0L, null, 0L, 0L, java.util.Collections.emptySet(), 0, 0));
	}

	private void diary(String region, String tier, boolean done)
	{
		JsonObject diaries = achievements.has("diaries")
			? achievements.getAsJsonObject("diaries") : new JsonObject();
		JsonObject r = diaries.has(region)
			? diaries.getAsJsonObject(region) : new JsonObject();
		r.addProperty(tier, done);
		diaries.add(region, r);
		achievements.add("diaries", diaries);
	}

	private GrindBook.PetChase chase(String pet)
	{
		Map<String, GrindBook.PetChase> out = new GrindBook(new Gson())
			.petChases(clog, ledger, counters, skills, achievements, PETS);
		return out.get(pet.toLowerCase(java.util.Locale.ROOT));
	}

	@Test
	public void theProtectorIsReadOffTheRiftSearches()
	{
		kc("Guardians of the Rift", 5_218);
		ledger("Guardians of the Rift", 4_955);
		GrindBook.PetChase c = chase("Abyssal protector");
		assertEquals(72.9, c.percentileDry, 0.05);
		assertEquals(5_218, c.kc);
		assertEquals(1, c.sources.size());
		assertEquals(4_000, c.sources.get(0).rate);
		assertEquals("Guardians of the Rift", c.activity);
		assertEquals("searches", c.unit);
		assertEquals(0, c.level);
	}

	@Test
	public void theChompyIsPricedOnTheKillItCountsNotThePluckItCannot()
	{
		diary("western", "elite", true);
		ledger("Chompy bird", 295);
		GrindBook.PetChase c = chase("Chompy chick");
		assertEquals(44.6, c.percentileDry, 0.05);
		assertEquals(295, c.kc);
		assertEquals(500, c.sources.get(0).rate);
	}

	@Test
	public void onlyMasterCasketsRollTheBloodhound()
	{
		ledger("Clue Scroll (Elite)", 6);
		ledger("Clue Scroll (Hard)", 11);
		assertNull(chase("Bloodhound"));

		ledger("Clue Scroll (Master)", 300);
		GrindBook.PetChase c = chase("Bloodhound");
		assertEquals(25.9, c.percentileDry, 0.05);
		assertEquals(300, c.kc);
		assertEquals("Master clues", c.activity);
		assertEquals("caskets", c.unit);
	}

	@Test
	public void onlyTheHighGambleRollsTheQueen()
	{
		ledger("Barbarian Assault low gamble", 4_000);
		ledger("Barbarian Assault high gamble", 611);
		GrindBook.PetChase c = chase("Pet penance queen");
		assertEquals(45.7, c.percentileDry, 0.05);
		assertEquals(611, c.kc);
		assertEquals(1_000, c.sources.get(0).rate);
	}

	@Test
	public void theCreatorTakesTheFullerOfTwoSpellingsNotTheirSum()
	{
		kc("Soul Wars", 346);
		GrindBook.PetChase log = chase("Lil' creator");
		assertEquals(346, log.kc);
		assertEquals(57.9, log.percentileDry, 0.05);
		assertEquals(400, log.sources.get(0).rate);

		ledger("Spoils of war", 402);
		GrindBook.PetChase both = chase("Lil' creator");
		assertEquals(402, both.kc);
		assertEquals(1, both.sources.size());
	}

	@Test
	public void quetzinCountsExpertAndMasterSacksAndNotTheGuildCount()
	{
		kc("Hunter Guild", 4_000);
		ledger("Hunters' loot sack (basic)", 900);
		ledger("Hunters' loot sack (adept)", 700);
		ledger("Hunters' loot sack (expert)", 502);
		ledger("Hunters' loot sack (master)", 110);
		GrindBook.PetChase c = chase("Quetzin");
		assertEquals(612, c.kc);
		assertEquals(2, c.sources.size());
		assertEquals(45.8, c.percentileDry, 0.05);
		assertEquals("Expert sacks", c.sources.get(0).boss);
	}

	@Test
	public void yamiIsPricedAtTheOrdinaryKill()
	{
		kc("Yama", 1_204);
		GrindBook.PetChase c = chase("Yami");
		assertEquals(2_500, c.sources.get(0).rate);
		assertEquals(38.2, c.percentileDry, 0.05);
		assertEquals(1_204, c.kc);
	}

	@Test
	public void theGryphonIsOneSourceHoweverItIsSpelled()
	{
		kc("Shellbane Gryphon", 88);
		ledger("Shellbane gryphon", 214);
		GrindBook.PetChase c = chase("Gull");
		assertEquals(1, c.sources.size());
		assertEquals(214, c.kc);
		assertEquals(3_000, c.sources.get(0).rate);
	}

	@Test
	public void beefAndAggyReadTheFullerRecordNotTheStalerPage()
	{
		kc("Brutus", 2);
		ledger("Brutus", 75);
		assertEquals(75, chase("Beef").kc);
		assertEquals(7.2, chase("Beef").percentileDry, 0.05);

		kc("The Mad Angel", 6);
		ledger("Mad Angel", 124);
		GrindBook.PetChase aggy = chase("Aggy");
		assertEquals(124, aggy.kc);
		assertEquals(1, aggy.sources.size());
		assertEquals(6.0, aggy.percentileDry, 0.05);
	}

	@Test
	public void tinyTemporIsReadOffTheRewardPoolAndNotTheSubdue()
	{
		kc("Tempoross", 46);
		ledger("Reward pool (Tempoross)", 114);
		GrindBook.PetChase c = chase("Tiny tempor");
		assertEquals(114, c.kc);
		assertEquals(1, c.sources.size());
		assertEquals("Reward pool", c.sources.get(0).boss);
		assertEquals(8_000, c.sources.get(0).rate);
		assertEquals("Tempoross", c.activity);
		assertEquals("searches", c.unit);
		assertEquals(1.4, c.percentileDry, 0.05);
	}

	@Test
	public void theTemporossCasketIsNotASecondRollUnit()
	{
		ledger("Reward pool (Tempoross)", 114);
		ledger("Casket (Tempoross)", 25);
		GrindBook.PetChase c = chase("Tiny tempor");
		assertEquals(114, c.kc);
		assertEquals(1, c.sources.size());
	}

	@Test
	public void aTemporossSubdueCountAloneBuysNoChase()
	{
		kc("Tempoross", 46);
		assertNull(chase("Tiny tempor"));
	}

	@Test
	public void thePetsNoCounterCanAskForGetNoRow()
	{
		kc("Doom of Mokhaiotl", 400);
		kc("Maggot King", 900);
		kc("Wyrmscraig Goat", 1_500);
		kc("Zalcano", 2_023);
		assertNull(chase("Dom"));
		assertNull(chase("Maggot marquess"));
		assertNull(chase("Mr McGroot"));
		assertNotNull(chase("Smolcano"));
		assertNull(chase("Smolcano").activity);
	}
}
