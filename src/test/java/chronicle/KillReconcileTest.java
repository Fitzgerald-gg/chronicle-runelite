package chronicle;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class KillReconcileTest
{
	private static JsonObject clog(long pageCounter, Long labelledKills, Long killLog)
	{
		JsonObject cl = new JsonObject();
		JsonObject kcs = new JsonObject();
		kcs.addProperty("Wintertodt", pageCounter);
		cl.add("kcs", kcs);
		if (labelledKills != null)
		{
			JsonObject lines = new JsonObject();
			JsonObject page = new JsonObject();
			page.addProperty("Rewards claimed", pageCounter);
			page.addProperty("Wintertodt kills", labelledKills);
			lines.add("Wintertodt", page);
			cl.add("kc_lines", lines);
		}
		if (killLog != null)
		{
			JsonObject log = new JsonObject();
			log.addProperty("Wintertodt", killLog);
			cl.add("slayer_kcs", log);
		}
		return cl;
	}

	private static long reconciled(JsonObject cl, List<LocalStore.SourceRow> sources)
	{
		Map<String, Long> out = LocalStore.reconciledKills(
			cl, sources, new HashMap<>(), new HashMap<>());
		Long v = out.get("Wintertodt");
		return v == null ? 0L : v;
	}

	@Test
	public void aNamedLineBeatsTheCounterBesideIt()
	{
		assertEquals(447L, reconciled(clog(1078L, 447L, null), new ArrayList<>()));
	}

	@Test
	public void aStaleKillLogDoesNotUndoTheNamedLine()
	{
		assertEquals("the Kill Log is 200 behind the page's own line",
			447L, reconciled(clog(1078L, 447L, 247L), new ArrayList<>()));
	}

	@Test
	public void aFresherKillLogStillWins()
	{
		assertEquals(500L, reconciled(clog(1078L, 447L, 500L), new ArrayList<>()));
	}

	@Test
	public void theLedgerFloorDoesNotReadmitThePageCounter()
	{
		List<LocalStore.SourceRow> sources = new ArrayList<>();
		sources.add(new LocalStore.SourceRow("Wintertodt", 0, 10, 0L, null, 0L, 0L, java.util.Collections.emptySet(), 0, 0));
		assertEquals("the page counter is not evidence of kills",
			447L, reconciled(clog(1078L, null, 447L), sources));
	}

	@Test
	public void theLedgerStillFloors()
	{
		List<LocalStore.SourceRow> sources = new ArrayList<>();
		sources.add(new LocalStore.SourceRow("Wintertodt", 900, 900, 0L, null, 0L, 0L, java.util.Collections.emptySet(), 0, 0));
		assertEquals(900L, reconciled(clog(1078L, 447L, null), sources));
	}
}
