/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Slf4j
@Singleton
class CloudSync
{
	private static final String KEY_TOKEN = "token";
	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
	private static final Set<String> PRIVATE = Set.of("untakenLootValue", "untakenLootCount", "resourcesGatheredValue");
	private static final String[] ACCOUNT_TYPES = {"", "ironman", "uim", "hcim", "gim", "hcgim", "ugim"};
	private final OkHttpClient http;
	private final Gson gson;
	private final Client client;
	private final ClientThread thread;
	private final ConfigManager configs;
	private final ChronicleConfig config;
	private final ClogCapture clog;
	private final AchievementSync achievements;
	private final LocalStore store;
	private ChroniclePlugin plugin;
	private volatile long hash = -1L;
	private volatile String rsn;
	private volatile String token;
	private volatile String name;
	private volatile String type;
	private volatile Map<String, Integer> stats;

	@Inject
	CloudSync(OkHttpClient http, Gson gson, Client client, ClientThread thread, ConfigManager configs,
		ChronicleConfig config, ClogCapture clog, AchievementSync achievements, LocalStore store)
	{
		this.http = http.newBuilder()
			.connectTimeout(10, TimeUnit.SECONDS)
			.readTimeout(15, TimeUnit.SECONDS)
			.writeTimeout(15, TimeUnit.SECONDS)
			.build();
		this.gson = gson;
		this.client = client;
		this.thread = thread;
		this.configs = configs;
		this.config = config;
		this.clog = clog;
		this.achievements = achievements;
		this.store = store;
	}

	void attach(ChroniclePlugin plugin)
	{
		this.plugin = plugin;
	}

	boolean active()
	{
		return config.cloudSync() && !config.serverBaseUrl().trim().isEmpty();
	}

	String rsn()
	{
		String r = rsn;
		return active() && r != null && !r.isEmpty() ? r : null;
	}

	void adopt(String who)
	{
		String override = trim(config.manualToken());
		String t = stored();
		if (override != null && !override.equals(t))
		{
			configs.setRSProfileConfiguration(ChronicleConfig.GROUP, KEY_TOKEN, override);
			t = override;
		}
		if (t == null)
		{
			plugin.refreshPanel();
			return;
		}
		rsn = who;
		token = t;
		name = who;
		plugin.refreshPanel();
		push();
	}

	void pushNow()
	{
		if (!active())
		{
			plugin.chat("Chronicle: enable cloud sync (and set a server) under Advanced in the "
				+ "plugin settings first.");
			return;
		}
		thread.invoke(this::push);
	}

	void push()
	{
		String who = ChronicleEventCapture.playerName(client);
		if (!active() || client.getGameState() != GameState.LOGGED_IN || who == null)
		{
			return;
		}
		hash = client.getAccountHash();
		String t = stored();
		if (t == null)
		{
			return;
		}
		if (clog.isDirty())
		{
			JsonObject body = gson.toJsonTree(clog.snapshot()).getAsJsonObject();
			body.addProperty("playerName", who);
			post("clog", t, body, ok -> { });
			clog.clearDirty();
		}
		JsonObject done = achievements.snapshot();
		if (achievements.changedSince(done))
		{
			JsonObject body = new JsonObject();
			body.addProperty("playerName", who);
			body.add("achievements", done);
			post("achievements", t, body, ok ->
			{
				if (ok)
				{
					achievements.markSynced(done);
				}
			});
		}
		if (!who.equals(plugin.localName()) || !store.isReadyFor(who))
		{
			return;
		}
		store.setTrackers(plugin.sessionView(), who);
		Map<String, Integer> now = stats(who);
		if (now.isEmpty())
		{
			return;
		}
		int v = client.getVarbitValue(VarbitID.IRONMAN);
		token = t;
		name = who;
		stats = now;
		type = v >= 0 && v < ACCOUNT_TYPES.length ? ACCOUNT_TYPES[v] : "";
		rsn = who;
		log.debug("pushing {} counters for {}", now.size(), who);
		send(t, who, now, type, plugin.skillsJson());
	}

	void logout(String ready)
	{
		if (ready != null)
		{
			Map<String, Integer> fresh = stats(ready);
			if (!fresh.isEmpty())
			{
				stats = fresh;
			}
		}
		String t = token;
		String who = name;
		String ty = type;
		Map<String, Integer> last = stats;
		token = null;
		name = null;
		stats = null;
		type = null;
		rsn = null;
		if (active() && t != null && who != null && last != null && !last.isEmpty())
		{
			send(t, who, last, ty, null);
		}
	}

	void forget()
	{
		if (trim(config.manualToken()) == null)
		{
			configs.unsetRSProfileConfiguration(ChronicleConfig.GROUP, KEY_TOKEN);
		}
		token = null;
		name = null;
		rsn = null;
	}

	void event(String who, String kind, JsonObject data)
	{
		String t = active() ? stored() : null;
		if (t == null)
		{
			return;
		}
		JsonObject body = new JsonObject();
		body.addProperty("playerName", who);
		long h = client.getAccountHash();
		if (h != -1L)
		{
			body.addProperty("accountHash", String.valueOf(h));
		}
		body.addProperty("type", kind);
		body.addProperty("eventId", UUID.randomUUID().toString());
		body.add("data", data);
		post("events", t, body, ok -> { });
	}

	private Map<String, Integer> stats(String who)
	{
		Map<String, Integer> out = new HashMap<>();
		if (!store.isReadyFor(who))
		{
			return out;
		}
		store.trackersSnapshot().forEach((k, v) ->
		{
			if (v != null && v > 0 && !PRIVATE.contains(k))
			{
				out.put(k, (int) Math.min(Integer.MAX_VALUE, v));
			}
		});
		return out;
	}

	private void send(String t, String who, Map<String, Integer> now, String ty, JsonObject skills)
	{
		JsonObject body = new JsonObject();
		body.addProperty("playerName", who);
		if (hash != -1L)
		{
			body.addProperty("accountHash", String.valueOf(hash));
		}
		if (ty != null && !ty.isEmpty())
		{
			body.addProperty("accountType", ty);
		}
		JsonObject s = new JsonObject();
		now.forEach(s::addProperty);
		body.add("stats", s);
		if (skills != null && skills.size() > 0)
		{
			body.add("skills", skills);
		}
		post("counters", t, body, ok -> plugin.refreshPanel());
	}

	private String stored()
	{
		return trim(configs.getRSProfileConfiguration(ChronicleConfig.GROUP, KEY_TOKEN));
	}

	private void post(String endpoint, String t, JsonObject body, Consumer<Boolean> done)
	{
		HttpUrl url = url(config.serverBaseUrl(), endpoint, t);
		if (url == null)
		{
			done.accept(false);
			return;
		}
		Request request = new Request.Builder().url(url).post(RequestBody.create(JSON, gson.toJson(body))).build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("{} push failed", endpoint, e);
				done.accept(false);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				boolean ok = response.isSuccessful();
				response.close();
				if (!ok)
				{
					log.debug("{} push HTTP {}", endpoint, response.code());
				}
				done.accept(ok);
			}
		});
	}

	private static HttpUrl url(String base, String endpoint, String t)
	{
		HttpUrl root = base == null || base.trim().isEmpty() ? null : HttpUrl.parse(base.trim().replaceAll("/+$", ""));
		if (root == null)
		{
			return null;
		}
		HttpUrl.Builder b = root.newBuilder().addPathSegment("api").addPathSegment(endpoint);
		for (String seg : t.split("/"))
		{
			if (!seg.isEmpty())
			{
				b.addPathSegment(seg);
			}
		}
		return b.build();
	}

	static String trim(String s)
	{
		return s == null || s.trim().isEmpty() ? null : s.trim();
	}
}
