/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LedgerObtainedTest
{
	private static JsonObject clog(String boss, long kc)
	{
		JsonObject clog = new JsonObject();
		JsonObject kcs = new JsonObject();
		kcs.addProperty(boss, kc);
		clog.add("kcs", kcs);
		return clog;
	}

	private static LocalStore.SourceRow row(String source, int kc, String... looted)
	{
		Set<String> names = new HashSet<>();
		Collections.addAll(names, looted);
		return new LocalStore.SourceRow(source, kc, kc, 0L, null, 0L, 0L, names, 0, 0);
	}

	private static Set<String> chased(JsonObject clog, List<LocalStore.SourceRow> sources)
	{
		Set<String> out = new HashSet<>();
		for (GrindBook.GrindRow g : new GrindBook(new Gson()).grinds(clog, sources))
		{
			out.add(g.boss + " / " + g.item);
		}
		return out;
	}

	@Test
	public void aLootedUniqueIsNotChased()
	{
		List<LocalStore.SourceRow> sources = new ArrayList<>();
		sources.add(row("Vorkath", 156, "draconic visage"));
		Set<String> chased = chased(clog("Vorkath", 156), sources);
		assertFalse(chased.toString(), chased.contains("Vorkath / Draconic visage"));
		assertTrue(chased.toString(), chased.contains("Vorkath / Skeletal visage"));
	}

	@Test
	public void theLogAloneStillChasesIt()
	{
		List<LocalStore.SourceRow> sources = new ArrayList<>();
		sources.add(new LocalStore.SourceRow("Vorkath", 156, 156, 0L, null, 0L, 0L, java.util.Collections.emptySet(), 0, 0));
		assertTrue(chased(clog("Vorkath", 156), sources).contains("Vorkath / Draconic visage"));
	}

	@Test
	public void aNameLootedAnywhereCountsOnEveryPage()
	{
		List<LocalStore.SourceRow> sources = new ArrayList<>();
		sources.add(row("Mad Angel", 3, "dragon med helm"));
		Set<String> chased = chased(clog("Barrows Chests", 25), sources);
		assertFalse(chased.toString(), chased.contains("Barrows Chests / Dragon med helm"));
	}

	@Test
	public void aLootedPetIsNotAPetChase()
	{
		List<LocalStore.SourceRow> sources = new ArrayList<>();
		sources.add(row("Zalcano", 2023, "smolcano"));
		Map<String, GrindBook.PetChase> chases = new GrindBook(new Gson()).petChases(
			clog("Zalcano", 2023), sources, new java.util.HashMap<>(), new java.util.HashMap<>(),
			new JsonObject(), Collections.singletonList("Smolcano"));
		assertFalse(chases.keySet().toString(), chases.containsKey("smolcano"));
	}
}
