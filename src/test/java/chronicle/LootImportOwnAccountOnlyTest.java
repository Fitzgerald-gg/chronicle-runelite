/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.lang.reflect.Method;
import net.runelite.http.api.loottracker.LootRecordType;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LootImportOwnAccountOnlyTest
{
	private static boolean wanted(String key) throws Exception
	{
		Method m = ChroniclePlugin.class.getDeclaredMethod("wantedLootType", String.class);
		m.setAccessible(true);
		return (Boolean) m.invoke(null, key);
	}

	@Test
	public void aPvpRecordIsNotInherited() throws Exception
	{
		assertFalse("another player's loot record was adopted",
			wanted("drops_PLAYER_SomeOtherPlayer"));
		assertFalse("a display name with spaces is still a player",
			wanted("drops_PLAYER_Some Other Player"));
	}

	@Test
	public void theKindsChronicleKeepsAreStillInherited() throws Exception
	{
		assertTrue(wanted("drops_NPC_Abyssal demon"));
		assertTrue(wanted("drops_EVENT_Barrows"));
		assertTrue(wanted("drops_PICKPOCKET_Master Farmer"));
	}

	@Test
	public void anUnknownKindIsNotInheritedByDefault() throws Exception
	{
		assertFalse("a kind nobody has considered was let in",
			wanted("drops_SOMETHINGNEW_Whatever"));
		assertFalse("the bare prefix is not a type", wanted("drops_Abyssal demon"));
		assertFalse(wanted(null));
	}

	@Test
	public void everyTypeTheClientKnowsIsDecidedOneWayOrTheOther() throws Exception
	{
		for (LootRecordType t : LootRecordType.values())
		{
			String key = "drops_" + t.name() + "_Thing";
			if (t == LootRecordType.PLAYER)
			{
				assertFalse("PLAYER must never be inherited", wanted(key));
			}
			else
			{
				assertTrue(t + " should be inherited", wanted(key));
			}
		}
	}
}
