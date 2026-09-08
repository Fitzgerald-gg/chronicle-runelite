/*
 * Copyright (c) 2026, Chronicle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the conditions of the
 * BSD 2-Clause License (see LICENSE) are met.
 */
package chronicle;

import com.google.gson.Gson;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;

/**
 * A journal written by the build that stamped every mid-task kill as a task kill
 * carries off-task monsters in its slayer segments. Loading it drops them and takes
 * their counts off the segment's kills; a clean segment is left exactly as it was.
 */
public class SlayerSegmentHealTest
{
	private LocalStore store;
	private File dir;

	@Before
	public void setUp() throws Exception
	{
		store = new LocalStore(Mockito.mock(ItemManager.class), new Gson());
		dir = Files.createTempDirectory("chronicle-slayer-heal").toFile();
	}

	private void write(String journal) throws Exception
	{
		Files.write(new File(dir, "tester.json").toPath(),
			journal.getBytes(StandardCharsets.UTF_8));
	}

	private static long count(List<LocalStore.UntakenRow> rows, String monster)
	{
		for (LocalStore.UntakenRow r : rows)
		{
			if (r.name.equals(monster))
			{
				return r.qty;
			}
		}
		return -1;
	}

	@Test
	public void offTaskMonstersAreDroppedAndTheirKillsTakenOff() throws Exception
	{
		write("{\"schema\":1,\"rsn\":\"Tester\",\"slayer\":{\"tasks\":["
			+ "{\"task\":\"Nechryael\",\"kills\":4,\"assignment\":200,\"value\":900,\"open\":true,"
			+ "\"monsters\":{\"Nechryael\":1,\"Man\":1,\"Baby impling\":1,\"Eclectic impling\":1},"
			+ "\"items\":{\"Bones\":{\"id\":526,\"qty\":4,\"value\":900}}}]}}");
		store.load(dir, "Tester");
		ChronicleApiClient.SlayerTask t = store.slayerJourney().tasks.get(0);
		assertEquals(1, t.kills);
		List<LocalStore.UntakenRow> mons = store.slayerTaskMonsters(0);
		assertEquals(1, mons.size());
		assertEquals(1, count(mons, "Nechryael"));
		assertEquals(-1, count(mons, "Man"));
		// the aggregated bag and value cannot be split per monster, so they stand
		assertEquals(900, t.totalValue);
		assertEquals(4, store.slayerTaskItems(0).get(0).qty);
	}

	@Test
	public void aCleanSegmentIsLeftAlone() throws Exception
	{
		write("{\"schema\":1,\"rsn\":\"Tester\",\"slayer\":{\"tasks\":["
			+ "{\"task\":\"Dust devils\",\"kills\":208,\"assignment\":208,\"value\":5,\"open\":true,"
			+ "\"monsters\":{\"Dust devil\":206,\"Choke devil\":2}}]}}");
		store.load(dir, "Tester");
		ChronicleApiClient.SlayerTask t = store.slayerJourney().tasks.get(0);
		assertEquals(208, t.kills);
		List<LocalStore.UntakenRow> mons = store.slayerTaskMonsters(0);
		assertEquals(2, mons.size());
		assertEquals(206, count(mons, "Dust devil"));
		assertEquals(2, count(mons, "Choke devil"));
	}

	@Test
	public void aClosedSegmentIsNotReVerifiedByNameAlone() throws Exception
	{
		// Imported from the server, which passed these guards on their Prifddinas ids.
		// The journal keeps no ids, so re-running the name tier here would wrongly
		// throw the whole task out.
		write("{\"schema\":1,\"rsn\":\"Tester\",\"slayer\":{\"tasks\":["
			+ "{\"task\":\"Elves\",\"kills\":128,\"assignment\":128,\"value\":5,\"open\":false,"
			+ "\"monsters\":{\"Guard\":128}}]}}");
		store.load(dir, "Tester");
		assertEquals(128, store.slayerJourney().tasks.get(0).kills);
		assertEquals(128, count(store.slayerTaskMonsters(0), "Guard"));
	}

	@Test
	public void aSegmentWhoseMonstersAllFailIsZeroedNotDeleted() throws Exception
	{
		write("{\"schema\":1,\"rsn\":\"Tester\",\"slayer\":{\"tasks\":["
			+ "{\"task\":\"Nechryael\",\"kills\":2,\"assignment\":0,\"value\":0,\"open\":true,"
			+ "\"monsters\":{\"Man\":1,\"Hoop Snake\":1}}]}}");
		store.load(dir, "Tester");
		ChronicleApiClient.SlayerJourney j = store.slayerJourney();
		assertEquals(1, j.tasks.size());
		assertEquals(0, j.tasks.get(0).kills);
		assertEquals(0, store.slayerTaskMonsters(0).size());
	}
}
