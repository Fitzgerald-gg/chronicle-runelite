/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

import org.junit.Test;

import static chronicle.SlayerTaskBook.UNKNOWN_ID;
import static chronicle.SlayerTaskBook.onTask;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Whether a kill counts toward the live task: the NPC id settles it when the table
 * knows the id, and only an unknown id falls back to the name (root or variant).
 */
public class SlayerTaskBookTest
{
	private static final int NECHRYAEL = 11;
	private static final int PRIF_GUARD = 9183;
	private static final int DUST_DEVIL = 7249;

	@Test
	public void theTableLoads()
	{
		assertEquals(12, SlayerTaskBook.get().idCount());
		assertEquals(150, SlayerTaskBook.get().taskCount());
	}

	@Test
	public void onTaskById()
	{
		assertTrue(onTask("Nechryael", NECHRYAEL, "Nechryael"));
	}

	@Test
	public void guardAmbiguityResolvesById()
	{
		// a Prifddinas guard's id maps to Elves
		assertTrue(onTask("Guard", PRIF_GUARD, "Elves"));
		// a Varrock guard's id is unmapped, and "elve" is not in "guard"
		assertFalse(onTask("Guard", 3010, "Elves"));
		assertFalse(onTask("Guard", UNKNOWN_ID, "Elves"));
	}

	@Test
	public void onTaskByVariantName()
	{
		assertTrue(onTask("Choke devil", UNKNOWN_ID, "Dust devils"));
		assertTrue(onTask("Reanimated abyssal", UNKNOWN_ID, "Abyssal demons"));
	}

	@Test
	public void onTaskByRoot()
	{
		assertTrue(onTask("Blue dragon", UNKNOWN_ID, "Blue dragons"));
		assertTrue(onTask("Abyssal Sire", UNKNOWN_ID, "The Abyssal Sire"));
		assertEquals("blue dragon", SlayerTaskBook.root("blue dragons"));
		assertEquals("abyssal sire", SlayerTaskBook.root("the abyssal sire"));
		// four letters or fewer keep their s
		assertEquals("ants", SlayerTaskBook.root("ants"));
	}

	@Test
	public void offTaskKillsDoNotCount()
	{
		assertFalse(onTask("Man", UNKNOWN_ID, "Nechryael"));
		assertFalse(onTask("Baby impling", UNKNOWN_ID, "Nechryael"));
		assertFalse(onTask("Eclectic impling", UNKNOWN_ID, "Nechryael"));
	}

	@Test
	public void aKnownIdBeatsAMatchingName()
	{
		// the name says Nechryael, the id says Dust devils: the id wins
		assertFalse(onTask("Nechryael", DUST_DEVIL, "Nechryael"));
	}

	@Test
	public void taskLookupIsCaseInsensitive()
	{
		assertTrue(onTask("Choke devil", UNKNOWN_ID, "dust devils"));
		assertTrue(onTask("Nechryael", NECHRYAEL, "NECHRYAEL"));
	}
}
