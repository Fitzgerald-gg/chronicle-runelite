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

	private static JsonObject clog()
	{
		return new Gson().fromJson("{\"kcs\":{"
			+ "\"Wintertodt\":1078,\"Tempoross\":46,\"Barbarian Assault\":2,"
			+ "\"Barrows Chests\":25,\"General Graardor\":14},"
			+ "\"kc_lines\":{"
			+ "\"Wintertodt\":{\"Rewards claimed\":1078,\"Wintertodt kills\":447},"
			+ "\"Tempoross\":{\"Personal Best: 3\":46,"
			+ "\"Reward permits claimed\":1048,\"Tempoross kills\":455},"
			+ "\"Zalcano\":{\"Zalcano kills\":2023},"
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

	@Test
	public void aPageWithNoKillLineHasNoKillCount()
	{
		Map<String, Long> kc = LocalStore.pageKillLines(clog());
		assertFalse("a gamble count is not a kill count", kc.containsKey("Barbarian Assault"));
		assertFalse("an empty header is not a kill count", kc.containsKey("Abyssal Sire"));
	}

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

	@Test
	public void aPageWithNoLinesAtAllKeepsItsBareFigure() throws Exception
	{
		Map<String, Long> c = counts(clog());
		assertEquals(Long.valueOf(25L), c.get("barrows chests"));
		assertEquals(Long.valueOf(14L), c.get("general graardor"));
	}

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
