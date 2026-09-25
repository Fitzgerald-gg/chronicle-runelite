package chronicle;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * The order the readings are weighed in, pinned.
 *
 * <p>Three of them can disagree about one fight: the collection log page's first
 * number (which need not be counting kills at all), the page's own labelled kills
 * line, and the Kill Log. Wintertodt is the standing example, where the page's
 * first number counts rewards claimed and reads 1,078 against 447 killed.
 */
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

	/** The labelled line beats the page's first number. */
	@Test
	public void aNamedLineBeatsTheCounterBesideIt()
	{
		assertEquals(447L, reconciled(clog(1078L, 447L, null), new ArrayList<>()));
	}

	/**
	 * And it is not then pulled back down by a Kill Log nobody has opened since.
	 * Both are the game counting and both only count up, so the later reading is
	 * the larger one.
	 */
	@Test
	public void aStaleKillLogDoesNotUndoTheNamedLine()
	{
		assertEquals("the Kill Log is 200 behind the page's own line",
			447L, reconciled(clog(1078L, 447L, 247L), new ArrayList<>()));
	}

	/** A Kill Log ahead of the page still wins, because it is the later reading. */
	@Test
	public void aFresherKillLogStillWins()
	{
		assertEquals(500L, reconciled(clog(1078L, 447L, 500L), new ArrayList<>()));
	}

	/**
	 * The ledger floors the result with what it has actually watched, and must not
	 * carry the page's counter back in with it.
	 *
	 * <p>The case that shows it is a page whose labels were never captured: with no
	 * labelled line, the counter itself is what step one holds, the Kill Log
	 * overrules it, and a floor taken from sourceKills raises every source back to
	 * that counter and re-admits the number the statement just overruled.
	 */
	@Test
	public void theLedgerFloorDoesNotReadmitThePageCounter()
	{
		List<LocalStore.SourceRow> sources = new ArrayList<>();
		sources.add(new LocalStore.SourceRow("Wintertodt", 0, 10, 0L, null, 0L, 0L, java.util.Collections.emptySet(), 0, 0));
		assertEquals("the page counter is not evidence of kills",
			447L, reconciled(clog(1078L, null, 447L), sources));
	}

	/** The ledger still raises a count it has genuinely seen more of. */
	@Test
	public void theLedgerStillFloors()
	{
		List<LocalStore.SourceRow> sources = new ArrayList<>();
		sources.add(new LocalStore.SourceRow("Wintertodt", 900, 900, 0L, null, 0L, 0L, java.util.Collections.emptySet(), 0, 0));
		assertEquals(900L, reconciled(clog(1078L, 447L, null), sources));
	}
}
