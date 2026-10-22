/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.GameState;
import org.junit.Test;
import static chronicle.Harness.after;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClogTest
{
	private static final int CHAINBODY = 3140;
	private static final int MYSTIC_HAT = 4089;
	private static final int ANCIENT_PAGE = 11341;
	private static final int DAGGER = 13265;

	private final Harness h = new Harness().login()
		.item(CHAINBODY, "Dragon chainbody", 200000)
		.item(MYSTIC_HAT, "Mystic hat (dark)", 1000)
		.item(ANCIENT_PAGE, "Ancient page", 0)
		.item(DAGGER, "Abyssal dagger", 1000000);

	private JsonObject clog()
	{
		return h.save().journal().getAsJsonObject("collection_log");
	}

	private void sireAndSlayerPages()
	{
		h.clogPage("Abyssal Sire", Arrays.asList("Obtained: 2/9", "Abyssal Sire kills: 20"),
			13262, 1, 0, 4151, 1, 0, DAGGER, 0, 150);
		h.clogPage("Slayer", Arrays.asList("Obtained: 1/90"), CHAINBODY, 1, 0, 4151, 1, 150);
	}

	@Test
	public void aDrawnPageIsReadHeldSlotsOnlyAndReadingAgainReplacesIt()
	{
		sireAndSlayerPages();
		sireAndSlayerPages();
		JsonObject sire = clog().getAsJsonObject("by_cat").getAsJsonObject("Abyssal Sire");
		assertEquals(1, sire.get("Abyssal whip").getAsInt());
		assertEquals(1, sire.get("Abyssal orphan").getAsInt());
		assertFalse(sire.has("Abyssal dagger"));
	}

	@Test
	public void aPlayerOwnedHouseBookIsNotYourLog()
	{
		h.varbit(net.runelite.api.gameval.VarbitID.COLLECTION_POH_HOST_BOOK_OPEN, 1);
		sireAndSlayerPages();
		assertFalse(clog().has("by_cat") && clog().getAsJsonObject("by_cat").has("Abyssal Sire"));
	}

	@Test
	public void theTransmitBurstIsKeptOnceItSettles()
	{
		h.clogTransmit(4151, 1).clogTransmit(13262, 1).ticks(4);
		JsonObject items = clog().getAsJsonObject("clog_items");
		assertEquals(1, items.get("Abyssal whip").getAsInt());
		assertEquals(1, items.get("Abyssal orphan").getAsInt());
	}

	@Test
	public void theLoginVarpsGiveTheLogWideTotals()
	{
		h.varp(2943, 120).varp(2944, 1500).state(GameState.LOGGED_IN).tick();
		JsonObject cl = clog();
		assertEquals(120, cl.get("finished").getAsInt());
		assertEquals(1500, cl.get("available").getAsInt());
		assertTrue(h.screen("Standing", "log").toString().contains("120"));
	}

	@Test
	public void aNewSlotIsInTheJournalAndLightsItsPage()
	{
		h.clogPage("Slayer", Collections.emptyList(), CHAINBODY, 1, 0).save();
		h.chat("New item added to your collection log: Mystic hat (dark)");
		assertEquals("Mystic hat (dark)", h.feed("COLLECTION").get(0).get("itemName").getAsString());
		List<String> other = h.screen("Standing", "log", "clog:Other");
		assertEquals("2/90", after(other, "Slayer"));
		List<String> page = h.screen("Standing", "log", "clog:Other", "page:Slayer");
		assertTrue(page.toString(), page.stream().anyMatch(s -> s.startsWith("Landed")));
	}

	@Test
	public void slotsLightPerPageAndASharedNameOnlyWhereThePageHoldsIt()
	{
		sireAndSlayerPages();
		h.clogPage("My Notes", Collections.emptyList(), ANCIENT_PAGE, 1, 0, ANCIENT_PAGE, 1, 0, ANCIENT_PAGE, 1, 0,
			ANCIENT_PAGE, 0, 150);
		h.save();
		assertEquals("2/9 · 20 kc", after(h.screen("Standing", "log", "clog:Bosses"), "Abyssal Sire"));
		List<String> other = h.screen("Standing", "log", "clog:Other");
		assertEquals("1/90", after(other, "Slayer"));
		assertEquals("3/26", after(other, "My Notes"));
	}
}
