/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
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

public class UntakenReconcileTest
{
	private LocalStore store;
	private File dir;

	@Before
	public void setUp() throws Exception
	{
		store = new LocalStore(Mockito.mock(ItemManager.class), new Gson());
		dir = Files.createTempDirectory("chronicle-untaken").toFile();
	}

	private void write(String journal) throws Exception
	{
		Files.write(new File(dir, "tester.json").toPath(),
			journal.getBytes(StandardCharsets.UTF_8));
	}

	private long qtyOf(List<LocalStore.UntakenRow> rows, String item)
	{
		for (LocalStore.UntakenRow r : rows)
		{
			if (r.name.equals(item))
			{
				return r.qty;
			}
		}
		return -1;
	}

	@Test
	public void byItemTotalsAreRebuiltFromThePairs() throws Exception
	{
		write("{\"schema\":1,\"rsn\":\"Tester\","
			+ "\"untaken\":{\"Dust devil\":{\"qty\":12,\"value\":120}},"
			+ "\"untaken_items\":{\"Bones\":{\"qty\":999,\"value\":9990},"
			+ "\"Swamp tar\":{\"qty\":55,\"value\":550}},"
			+ "\"untaken_pairs\":{\"Dust devil\":{\"Bones\":{\"qty\":12,\"value\":120}}}}");
		store.load(dir, "Tester");
		List<LocalStore.UntakenRow> items = store.untakenItems();
		assertEquals(1, items.size());
		assertEquals(12, qtyOf(items, "Bones"));
		assertEquals(-1, qtyOf(items, "Swamp tar"));
	}

	@Test
	public void theByItemAndBySourceViewsAgreeAfterAReload() throws Exception
	{
		write("{\"schema\":1,\"rsn\":\"Tester\","
			+ "\"untaken\":{\"Dust devil\":{\"qty\":12,\"value\":120},"
			+ "\"Nechryael\":{\"qty\":5,\"value\":50}},"
			+ "\"untaken_items\":{\"Bones\":{\"qty\":999,\"value\":9990}},"
			+ "\"untaken_pairs\":{\"Dust devil\":{\"Bones\":{\"qty\":12,\"value\":120}},"
			+ "\"Nechryael\":{\"Bones\":{\"qty\":5,\"value\":50}}}}");
		store.load(dir, "Tester");
		long bySource = 0;
		for (LocalStore.UntakenRow r : store.untakenSources())
		{
			bySource += r.qty;
		}
		long byItem = 0;
		for (LocalStore.UntakenRow r : store.untakenItems())
		{
			byItem += r.qty;
		}
		assertEquals(17, bySource);
		assertEquals(bySource, byItem);
	}

	@Test
	public void aJournalWithNoPairsIsLeftAlone() throws Exception
	{
		write("{\"schema\":1,\"rsn\":\"Tester\","
			+ "\"untaken\":{\"Dust devil\":{\"qty\":12,\"value\":120}},"
			+ "\"untaken_items\":{\"Bones\":{\"qty\":999,\"value\":9990}}}");
		store.load(dir, "Tester");
		assertEquals(999, qtyOf(store.untakenItems(), "Bones"));
	}

	@Test
	public void pairsThatMissASourceAreNotTrustedToSumFrom() throws Exception
	{
		write("{\"schema\":1,\"rsn\":\"Tester\","
			+ "\"untaken\":{\"Dust devil\":{\"qty\":12,\"value\":120},"
			+ "\"Nechryael\":{\"qty\":5,\"value\":50}},"
			+ "\"untaken_items\":{\"Bones\":{\"qty\":17,\"value\":170}},"
			+ "\"untaken_pairs\":{\"Dust devil\":{\"Bones\":{\"qty\":12,\"value\":120}}}}");
		store.load(dir, "Tester");
		assertEquals(17, qtyOf(store.untakenItems(), "Bones"));
	}
}
