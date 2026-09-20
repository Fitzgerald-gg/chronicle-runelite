/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What a collection log page's header says, and which of it is a kill count.
 *
 * <p>A page header can carry several counters, and the plugin used to print
 * whichever one came out of the unlabelled figure beside them. On most pages
 * that is the kill count and the label was right. On the pages that carry more
 * than one line it was not, and both of those are in the owner's own journal:
 * Wintertodt's figure is rewards claimed, and Tempoross's is a personal best,
 * which is a TIME. The board printed "46 kc" for an account with 455 Tempoross
 * kills.
 */
public class ClogPageCountTest
{
	@SuppressWarnings("unchecked")
	private static Map<String, Long> counts(JsonObject cl) throws Exception
	{
		java.lang.reflect.Method m = ChroniclePanel.class
			.getDeclaredMethod("pageCounts", JsonObject.class);
		m.setAccessible(true);
		return (Map<String, Long>) m.invoke(null, cl);
	}

	/** A page header shaped the way the game writes the awkward ones. */
	private static JsonObject clog()
	{
		return new Gson().fromJson("{\"kcs\":{"
			// the unlabelled figures, as the game leaves them: two of these are
			// the wrong number for their page, and two are the only number there is
			+ "\"Wintertodt\":1078,\"Tempoross\":46,\"Barbarian Assault\":2,"
			+ "\"Barrows Chests\":25,\"General Graardor\":14},"
			+ "\"kc_lines\":{"
			// rewards first, kills second: the order the game gives them
			+ "\"Wintertodt\":{\"Rewards claimed\":1078,\"Wintertodt kills\":447},"
			// a personal best FIRST, which is a time and not a count of anything
			+ "\"Tempoross\":{\"Personal Best: 3\":46,"
			+ "\"Reward permits claimed\":1048,\"Tempoross kills\":455},"
			+ "\"Zalcano\":{\"Zalcano kills\":2023},"
			// a page whose only line is a gamble: no kill count exists for it
			+ "\"Barbarian Assault\":{\"High-level Gambles\":2},"
			+ "\"Abyssal Sire\":{}"
			+ "}}", JsonObject.class);
	}

	@Test
	public void aPageWithRewardsAndKillsCountsTheKills()
	{
		Map<String, Long> kc = LocalStore.pageKillLines(clog());
		assertEquals("rewards claimed is not a kill count",
			Long.valueOf(447L), kc.get("Wintertodt"));
	}

	/** TRAP: a personal best is a time. It must never become a number of kills. */
	@Test
	public void aPersonalBestIsNeverReadAsACount()
	{
		Map<String, Long> kc = LocalStore.pageKillLines(clog());
		assertEquals("the PB and the permits were both passed over for the kills",
			Long.valueOf(455L), kc.get("Tempoross"));
	}

	@Test
	public void aPageWithOnlyOneKillLineUsesIt()
	{
		assertEquals(Long.valueOf(2023L), LocalStore.pageKillLines(clog()).get("Zalcano"));
	}

	/**
	 * And a page that never gave a kill count gets none. Printing the figure it
	 * did give, under a "kc" label, is the whole defect.
	 */
	@Test
	public void aPageWithNoKillLineHasNoKillCount()
	{
		Map<String, Long> kc = LocalStore.pageKillLines(clog());
		assertFalse("a gamble count is not a kill count", kc.containsKey("Barbarian Assault"));
		assertFalse("an empty header is not a kill count", kc.containsKey("Abyssal Sire"));
	}

	/** The whole header is still reachable, in the game's own words. */
	@Test
	public void theHoverKeepsEveryLineThePageGave() throws Exception
	{
		java.lang.reflect.Method m = ChroniclePanel.class.getDeclaredMethod(
			"pageHeaderTip", JsonObject.class, String.class);
		m.setAccessible(true);
		String tip = (String) m.invoke(null, clog(), "Tempoross");
		assertTrue("the hover lost a line: " + tip, tip.contains("Personal Best: 3"));
		assertTrue(tip.contains("Reward permits claimed"));
		assertTrue(tip.contains("Tempoross kills"));
		assertEquals("a page with an empty header gets no hover at all",
			null, m.invoke(null, clog(), "Abyssal Sire"));
	}

	/**
	 * A page with no captured lines keeps its bare figure. Dropping those to fix
	 * the two mislabelled pages would have silently taken the count off thirty
	 * eight of the owner's forty nine pages, which is a bigger lie than the one
	 * being fixed.
	 */
	@Test
	public void aPageWithNoLinesAtAllKeepsItsBareFigure() throws Exception
	{
		Map<String, Long> c = counts(clog());
		assertEquals(Long.valueOf(25L), c.get("barrows chests"));
		assertEquals(Long.valueOf(14L), c.get("general graardor"));
	}

	/** And on the board, the lines win wherever there are lines. */
	@Test
	public void wherePagesHaveLinesTheLinesWin() throws Exception
	{
		Map<String, Long> c = counts(clog());
		assertEquals("rewards claimed beat the kill line", Long.valueOf(447L),
			c.get("wintertodt"));
		assertEquals("a personal best beat the kill line", Long.valueOf(455L),
			c.get("tempoross"));
		assertFalse("a gamble count was printed as a kill count",
			c.containsKey("barbarian assault"));
	}
}
