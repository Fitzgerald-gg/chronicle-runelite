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

public class PetUnlockGateTest
{
	private static final List<String> PETS = Arrays.asList(
		"Chompy chick", "Bloodhound", "Beaver", "Smolcano");

	private final Map<String, Long> counters = new HashMap<>();
	private final Map<String, long[]> skills = new HashMap<>();
	private final JsonObject clog = new JsonObject();
	private final List<LocalStore.SourceRow> ledger = new ArrayList<>();
	private final JsonObject achievements = new JsonObject();

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
		return new GrindBook(new Gson())
			.petChases(clog, ledger, counters, skills, achievements, PETS)
			.get(pet.toLowerCase(java.util.Locale.ROOT));
	}

	@Test
	public void theChompyChickIsNoChaseUntilTheEliteDiaryIsDone()
	{
		ledger("Chompy bird", 295);
		diary("western", "easy", true);
		diary("western", "medium", true);
		diary("western", "hard", true);
		diary("western", "elite", false);
		assertNull(chase("Chompy chick"));
	}

	@Test
	public void theEliteDiaryOpensTheChaseOnTheSameKills()
	{
		ledger("Chompy bird", 295);
		diary("western", "elite", true);
		GrindBook.PetChase c = chase("Chompy chick");
		assertNotNull(c);
		assertEquals(295, c.kc);
		assertEquals(44.6, c.percentileDry, 0.05);
		assertEquals(500, c.sources.get(0).rate);
	}

	@Test
	public void anUnreadSheetIsNotAnUnlock()
	{
		ledger("Chompy bird", 295);
		assertNull(chase("Chompy chick"));
		diary("kandarin", "elite", true);
		diary("western", "hard", true);
		assertNull(chase("Chompy chick"));
	}

	@Test
	public void everyOtherPetIsUntouchedByTheGate()
	{
		ledger("Chompy bird", 295);
		ledger("Clue Scroll (Master)", 300);
		counters.put("yewLogsChopped", 14_204L);
		skills.put("woodcutting", new long[]{92, 6_517_253L});
		JsonObject kcs = new JsonObject();
		kcs.addProperty("Zalcano", 2_023);
		clog.add("kcs", kcs);

		assertNotNull(chase("Bloodhound"));
		assertNotNull(chase("Beaver"));
		assertNotNull(chase("Smolcano"));
		assertNull(chase("Chompy chick"));
	}
}
