/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClogSlotLightingTest
{
	@SuppressWarnings("unchecked")
	private static boolean[] light(List<String> slots, Map<String, Long> page,
		Map<String, Long> all, Set<String> shared) throws Exception
	{
		Method m = ChroniclePanel.class.getDeclaredMethod("lightSlots", List.class,
			Map.class, Map.class, Set.class);
		m.setAccessible(true);
		return (boolean[]) m.invoke(null, slots, page, all, shared);
	}

	private static Map<String, Long> map(Object... kv)
	{
		Map<String, Long> m = new LinkedHashMap<>();
		for (int i = 0; i + 1 < kv.length; i += 2)
		{
			m.put((String) kv[i], ((Number) kv[i + 1]).longValue());
		}
		return m;
	}

	@Test
	public void aSharedNameDoesNotLightAPageTheGameSaysIsEmpty() throws Exception
	{
		Set<String> shared = new HashSet<>(Arrays.asList("abyssal whip"));
		List<String> slayerPage = Arrays.asList("Abyssal whip", "Black mask");

		boolean[] lit = light(slayerPage,
			map("black mask", 1L),
			map("abyssal whip", 4L, "black mask", 1L),
			shared);
		assertFalse("a whip from the Sire lit the Slayer page", lit[0]);
		assertTrue(lit[1]);
	}

	@Test
	public void theSameNameStillLightsThePageThatHoldsIt() throws Exception
	{
		Set<String> shared = new HashSet<>(Arrays.asList("abyssal whip"));
		boolean[] lit = light(Arrays.asList("Abyssal whip"),
			map("abyssal whip", 4L), map("abyssal whip", 4L), shared);
		assertTrue(lit[0]);
	}

	@Test
	public void aNameWithOneHomeStillLightsFromTheLogWideSet() throws Exception
	{
		boolean[] lit = light(Arrays.asList("Zombie shirt"),
			map(),
			map("zombie shirt", 1L),
			new HashSet<>());
		assertTrue("a unique name obtained since the scrape should light", lit[0]);
	}

	@Test
	public void aPageNeverReadFallsBackToTheLogWideSet() throws Exception
	{
		Set<String> shared = new HashSet<>(Arrays.asList("3rd age amulet"));
		boolean[] lit = light(Arrays.asList("3rd age amulet"),
			null, map("3rd age amulet", 1L), shared);
		assertTrue("a page with no capture has nothing else to go on", lit[0]);
	}

	@Test
	public void repeatedNamesOnAPageLightOncePerHeldCopy() throws Exception
	{
		List<String> myNotes = Arrays.asList("Ancient page", "Ancient page",
			"Ancient page", "Ancient page");
		boolean[] three = light(myNotes, map("ancient page", 3L),
			map("ancient page", 3L), new HashSet<>());
		assertArrayEquals(new boolean[]{true, true, true, false}, three);

		boolean[] collapsed = light(myNotes, map("ancient page", 1L),
			map("ancient page", 1L), new HashSet<>());
		assertArrayEquals("one held lights one slot",
			new boolean[]{true, false, false, false}, collapsed);
	}

	@SuppressWarnings("unchecked")
	@Test
	public void theSharedNamesComeFromTheTaxonomyItself() throws Exception
	{
		Method m = ChroniclePanel.class.getDeclaredMethod("sharedSlotNames",
			com.google.gson.Gson.class);
		m.setAccessible(true);
		Set<String> shared = (Set<String>) m.invoke(null, new com.google.gson.Gson());
		assertTrue("the taxonomy has plenty of names on more than one page",
			shared.size() > 50);
		assertTrue("an abyssal whip is on the Sire's page and the Slayer page",
			shared.contains("abyssal whip"));
		assertFalse("a name unique to its page must not be in here",
			shared.contains("zombie shirt"));
	}
}
