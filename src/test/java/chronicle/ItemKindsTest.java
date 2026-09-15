package chronicle;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The item taxonomy, and in particular the traps in it.
 *
 * <p>Every one of these assertions is a name that reads as one kind and is
 * another. They are the reason the rules are ordered rather than a pattern.
 */
public class ItemKindsTest
{
	@Test
	public void sixteenKinds()
	{
		List<String> kinds = ItemKinds.kinds();
		assertEquals(16, kinds.size());
		assertEquals("Runes", kinds.get(0));
	}

	@Test
	public void everyRuneIsARune()
	{
		for (String rune : new String[]{"Fire rune", "Earth rune", "Water rune",
			"Air rune", "Mind rune", "Body rune", "Chaos rune", "Death rune",
			"Blood rune", "Soul rune", "Nature rune", "Law rune", "Cosmic rune",
			"Astral rune", "Wrath rune", "Dust rune", "Mist rune", "Mud rune"})
		{
			assertEquals(rune, "Runes", ItemKinds.kindOf(rune));
		}
	}

	/**
	 * The whole reason this file exists. Eleven of these are in the owner's own
	 * ledger, and a substring search for "rune" hands back a weapon shop.
	 */
	@Test
	public void nothingMerelyMadeOfRuniteIsARune()
	{
		assertEquals("Weapons", ItemKinds.kindOf("Rune scimitar"));
		assertEquals("Weapons", ItemKinds.kindOf("Rune 2h sword"));
		assertEquals("Weapons", ItemKinds.kindOf("Rune axe"));
		assertEquals("Armour", ItemKinds.kindOf("Rune platebody"));
		assertEquals("Armour", ItemKinds.kindOf("Rune chainbody"));
		assertEquals("Armour", ItemKinds.kindOf("Rune boots"));
		assertEquals("Ammunition", ItemKinds.kindOf("Rune arrow"));
		assertEquals("Ammunition", ItemKinds.kindOf("Rune arrowtips"));
		assertEquals("Ammunition", ItemKinds.kindOf("Runite bolts"));
		assertEquals("Ores and bars", ItemKinds.kindOf("Runite ore"));
		assertEquals("Ores and bars", ItemKinds.kindOf("Runite bar"));
		assertEquals("Materials", ItemKinds.kindOf("Rune essence"));
		assertEquals("Materials", ItemKinds.kindOf("Pure essence"));
	}

	@Test
	public void theOtherKindsFileWhatTheyShould()
	{
		assertEquals("Herbs", ItemKinds.kindOf("Grimy ranarr weed"));
		assertEquals("Herbs", ItemKinds.kindOf("Snapdragon"));
		assertEquals("Seeds", ItemKinds.kindOf("Ranarr seed"));
		assertEquals("Bones and ashes", ItemKinds.kindOf("Dragon bones"));
		assertEquals("Bones and ashes", ItemKinds.kindOf("Malicious ashes"));
		assertEquals("Logs", ItemKinds.kindOf("Magic logs"));
		assertEquals("Ores and bars", ItemKinds.kindOf("Coal"));
		assertEquals("Gems", ItemKinds.kindOf("Uncut diamond"));
		assertEquals("Potions", ItemKinds.kindOf("Prayer potion(4)"));
		assertEquals("Hides and leather", ItemKinds.kindOf("Green dragonhide"));
		assertEquals("Food", ItemKinds.kindOf("Raw shark"));
		assertEquals("Food", ItemKinds.kindOf("Ugthanki kebab"));
		assertEquals("Coins and tokens", ItemKinds.kindOf("Coins"));
		assertEquals("Clues and caskets", ItemKinds.kindOf("Clue scroll (elite)"));
		assertEquals("Materials", ItemKinds.kindOf("Feather"));
	}

	/**
	 * Null is a real answer. Most of what drops is a unique, and inventing a
	 * kind for it would be worse than leaving it findable by name.
	 */
	@Test
	public void whatNothingClaimsAnswersNull()
	{
		assertNull(ItemKinds.kindOf("Abyssal head"));
		assertNull(ItemKinds.kindOf(null));
		assertNull(ItemKinds.kindOf("   "));
	}

	@Test
	public void caseDoesNotMatter()
	{
		assertEquals("Runes", ItemKinds.kindOf("fire rune"));
		assertEquals("Runes", ItemKinds.kindOf("FIRE RUNE"));
		assertEquals("Weapons", ItemKinds.kindOf("rune scimitar"));
	}

	@Test
	public void aKindCanBeNamedWhileItIsBeingTyped()
	{
		assertEquals("Runes", ItemKinds.named("run"));
		assertEquals("Runes", ItemKinds.named("Runes"));
		assertEquals("Herbs", ItemKinds.named("herb"));
		assertNull(ItemKinds.named("zzz"));
		assertNull(ItemKinds.named(""));
		assertNull(ItemKinds.named(null));
	}

	/** Asking twice is free, which a three hundred row board relies on. */
	@Test
	public void theAnswerIsRemembered()
	{
		String first = ItemKinds.kindOf("Fire rune");
		String again = ItemKinds.kindOf("Fire rune");
		assertEquals(first, again);
		assertNotEquals(ItemKinds.kindOf("Fire rune"), ItemKinds.kindOf("Rune scimitar"));
	}

	/** Every kind the strip offers must be reachable from some rule. */
	@Test
	public void everyKindOfferedIsAKindSomethingCanBe()
	{
		for (String kind : ItemKinds.kinds())
		{
			assertTrue(kind + " is offered but nothing matches it",
				kind.equals(ItemKinds.kindOf(exampleOf(kind))));
		}
	}

	private static String exampleOf(String kind)
	{
		switch (kind)
		{
			case "Runes": return "Fire rune";
			case "Herbs": return "Grimy torstol";
			case "Seeds": return "Snapdragon seed";
			case "Bones and ashes": return "Big bones";
			case "Logs": return "Yew logs";
			case "Ores and bars": return "Mithril ore";
			case "Gems": return "Uncut ruby";
			case "Potions": return "Super restore(4)";
			case "Ammunition": return "Adamant arrow";
			case "Weapons": return "Dragon scimitar";
			case "Armour": return "Dragon platelegs";
			case "Hides and leather": return "Black dragonhide";
			case "Food": return "Raw lobster";
			case "Materials": return "Fishing bait";
			case "Clues and caskets": return "Clue scroll (hard)";
			case "Coins and tokens": return "Coins";
			default: return "";
		}
	}
}
