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

/**
 * The drop ledger's bags count as obtained for dryness: a unique already looted
 * is never a chase, whether or not the stored collection log has caught up with
 * it (the unlock notification off, or the page not reopened since).
 */
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
		return new LocalStore.SourceRow(source, kc, kc, 0L, null, 0L, 0L, names);
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

	// 156 Vorkath with a Draconic visage in the bag and nothing in the log: the
	// visage is owned, its twin is still the chase.
	@Test
	public void aLootedUniqueIsNotChased()
	{
		List<LocalStore.SourceRow> sources = new ArrayList<>();
		sources.add(row("Vorkath", 156, "draconic visage"));
		Set<String> chased = chased(clog("Vorkath", 156), sources);
		assertFalse(chased.toString(), chased.contains("Vorkath / Draconic visage"));
		assertTrue(chased.toString(), chased.contains("Vorkath / Skeletal visage"));
	}

	// The same rows read with the log alone still chase the visage: the fold is
	// what removes it, not the rate book.
	@Test
	public void theLogAloneStillChasesIt()
	{
		List<LocalStore.SourceRow> sources = new ArrayList<>();
		sources.add(new LocalStore.SourceRow("Vorkath", 156, 156, 0L, null, 0L, 0L));
		assertTrue(chased(clog("Vorkath", 156), sources).contains("Vorkath / Draconic visage"));
	}

	// The ledger is read by name across every source, as the site's was: a dragon
	// med helm looted off a Mad Angel is the same helm the Barrows page lists.
	@Test
	public void aNameLootedAnywhereCountsOnEveryPage()
	{
		List<LocalStore.SourceRow> sources = new ArrayList<>();
		sources.add(row("Mad Angel", 3, "dragon med helm"));
		Set<String> chased = chased(clog("Barrows Chests", 25), sources);
		assertFalse(chased.toString(), chased.contains("Barrows Chests / Dragon med helm"));
	}

	// A pet sitting in a bag is owned on the pet page's chase too.
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
