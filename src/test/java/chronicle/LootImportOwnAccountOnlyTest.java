/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import java.lang.reflect.Method;
import net.runelite.http.api.loottracker.LootRecordType;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The inherited archive is subject to the same rule as the live feed.
 *
 * <p>Chronicle records one account: its owner's. The live path says so in as
 * many words and drops PLAYER loot outright, because on a PK the core plugin's
 * record carries the VICTIM'S display name and the contents of their inventory.
 *
 * <p>The first run inherits that plugin's whole archive, which is what makes a
 * late install start with the drops already on disk. It scanned for the bare
 * "drops_" prefix, so it inherited the PvP records too, and another player's
 * name arrived as a source row on this account's Loot board with their items
 * under it. The rule was enforced in one file and broken in another, twenty
 * lines of reasoning away.
 */
public class LootImportOwnAccountOnlyTest
{
	private static boolean wanted(String key) throws Exception
	{
		Method m = ChroniclePlugin.class.getDeclaredMethod("wantedLootType", String.class);
		m.setAccessible(true);
		return (Boolean) m.invoke(null, key);
	}

	/** TRAP: the victim of a PK, arriving as a source on the killer's board. */
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

	/**
	 * An allow list, not a block list: a record type the core plugin adds later
	 * stays out until somebody decides it belongs, rather than arriving because
	 * nobody thought to name it.
	 */
	@Test
	public void anUnknownKindIsNotInheritedByDefault() throws Exception
	{
		assertFalse("a kind nobody has considered was let in",
			wanted("drops_SOMETHINGNEW_Whatever"));
		assertFalse("the bare prefix is not a type", wanted("drops_Abyssal demon"));
		assertFalse(wanted(null));
	}

	/**
	 * And the allow list is built from the client's own enum, so a type this
	 * plugin has never heard of cannot be silently absent from the check.
	 */
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
