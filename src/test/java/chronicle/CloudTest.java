/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CloudTest
{
	private final BlockingQueue<Request> received = new LinkedBlockingQueue<>();
	private HttpServer server;
	private String url;

	private static final class Request
	{
		final String path;
		final JsonObject body;

		Request(String path, JsonObject body)
		{
			this.path = path;
			this.body = body;
		}
	}

	@Before
	public void listen() throws IOException
	{
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange ->
		{
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			received.add(new Request(exchange.getRequestURI().getPath(), new JsonParser().parse(body).getAsJsonObject()));
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
		});
		server.start();
		url = "http://127.0.0.1:" + server.getAddress().getPort();
	}

	@After
	public void stop()
	{
		server.stop(0);
	}

	private Request next(String endpoint) throws InterruptedException
	{
		long until = System.currentTimeMillis() + 5000;
		for (long left = 5000; left > 0; left = until - System.currentTimeMillis())
		{
			Request r = received.poll(left, TimeUnit.MILLISECONDS);
			if (r != null && r.path.startsWith("/api/" + endpoint + "/"))
			{
				return r;
			}
		}
		return null;
	}

	private static Harness played(Harness h)
	{
		return h.kill("Bear", 1, 526, 1).chat("Oh dear, you are dead!").tick();
	}

	@Test
	public void nothingIsSentWhileCloudSyncIsOff() throws InterruptedException
	{
		Harness h = new Harness().cloud(url, "abc").cloudOff().login();
		played(h).push().logout();
		assertNull(received.poll(700, TimeUnit.MILLISECONDS));
	}

	@Test
	public void countersGoUnderTheStoredTokenWithoutTheLedgerOnlyOnes() throws InterruptedException
	{
		Harness h = new Harness().cloud(url, "abc/def").login();
		h.edit(j ->
		{
			JsonObject trackers = j.getAsJsonObject("trackers");
			trackers.addProperty("untakenLootValue", 5000);
			trackers.addProperty("untakenLootCount", 12);
			trackers.addProperty("resourcesGatheredValue", 800);
		});
		played(h).push();
		Request counters = next("counters");
		assertNotNull("no counters push", counters);
		assertEquals("/api/counters/abc/def", counters.path);
		assertEquals("Tester", counters.body.get("playerName").getAsString());
		JsonObject stats = counters.body.getAsJsonObject("stats");
		assertEquals(stats.toString(), 1, stats.get("deaths").getAsInt());
		assertFalse(stats.has("untakenLootValue"));
		assertFalse(stats.has("untakenLootCount"));
		assertFalse(stats.has("resourcesGatheredValue"));
		assertEquals(12, h.tracker("untakenLootCount"));
	}

	@Test
	public void eachJournalEventIsPostedAsItHappens() throws InterruptedException
	{
		Harness h = new Harness().cloud(url, "abc").login();
		h.kill("Bear", 1, 526, 2);
		Request event = next("events");
		assertNotNull("no event posted", event);
		assertEquals("/api/events/abc", event.path);
		assertEquals("LOOT", event.body.get("type").getAsString());
		assertEquals("Bear", event.body.getAsJsonObject("data").get("source").getAsString());
		assertTrue(event.body.has("eventId"));
	}

	@Test
	public void aManualTokenReplacesTheStoredOne() throws InterruptedException
	{
		Harness h = new Harness().cloud(url, "old").manualToken("mine").login();
		played(h).push();
		Request counters = next("counters");
		assertNotNull(counters);
		assertEquals("/api/counters/mine", counters.path);
		assertEquals("mine", h.storedToken());
	}

	@Test
	public void loggingOutSendsTheLastCounters() throws InterruptedException
	{
		Harness h = new Harness().cloud(url, "abc").login();
		played(h).push();
		assertNotNull(next("counters"));
		received.clear();
		h.logout();
		Request last = next("counters");
		assertNotNull("no push at logout", last);
		assertTrue(last.body.getAsJsonObject("stats").size() > 0);
	}

	@Test
	public void movingToAnotherServerForgetsTheToken()
	{
		Harness h = new Harness().cloud(url, "abc").login();
		h.server(url + "/elsewhere");
		assertNull(h.storedToken());
	}
}
