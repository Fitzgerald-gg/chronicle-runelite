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
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.Reader;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

@Slf4j
@Singleton
public class ChronicleApiClient
{
	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

	private static final int MAX_BODY_CHARS = 1024 * 1024;

	private final OkHttpClient http;
	private final Gson gson;

	@Inject
	ChronicleApiClient(OkHttpClient http, Gson gson)
	{
		this.http = http.newBuilder()
			.connectTimeout(10, TimeUnit.SECONDS)
			.readTimeout(15, TimeUnit.SECONDS)
			.writeTimeout(15, TimeUnit.SECONDS)
			.build();
		this.gson = gson;
	}

	@RequiredArgsConstructor
	public static final class PushResult
	{
		public final boolean ok;
		public final int code;
		public final int accepted;
		public final int changed;
		@Nullable
		public final String error;
	}

	private volatile long accountHash = -1L;

	public void setAccountHash(long h)
	{
		this.accountHash = h;
	}

	private void addAccountHash(JsonObject payload)
	{
		long h = accountHash;
		if (h != -1L)
		{
			payload.addProperty("accountHash", String.valueOf(h));
		}
	}

	public void pushStats(String baseUrl, String token, String name,
		Map<String, Integer> stats, @Nullable String accountType,
		@Nullable JsonObject skills, Consumer<PushResult> onDone)
	{
		HttpUrl url = resolve(baseUrl, "api/counters/" + token);
		if (url == null)
		{
			onDone.accept(new PushResult(false, -1, 0, 0, "bad server URL"));
			return;
		}

		JsonObject payload = new JsonObject();
		payload.addProperty("playerName", name);
		addAccountHash(payload);
		if (accountType != null && !accountType.isEmpty())
		{
			payload.addProperty("accountType", accountType);
		}
		JsonObject statsObj = new JsonObject();
		for (Map.Entry<String, Integer> e : stats.entrySet())
		{
			if (e.getValue() != null)
			{
				statsObj.addProperty(e.getKey(), e.getValue());
			}
		}
		payload.add("stats", statsObj);
		if (skills != null && skills.size() > 0)
		{
			payload.add("skills", skills);
		}

		post(url, payload, new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("stat push failed", e);
				onDone.accept(new PushResult(false, -1, 0, 0, e.getMessage()));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					int code = r.code();
					JsonObject body = parse(r);
					if (code == 200)
					{
						int accepted = body != null ? optInt(body, "accepted") : 0;
						int changed = body != null ? optInt(body, "changed") : 0;
						onDone.accept(new PushResult(true, code, accepted, changed, null));
					}
					else
					{
						String err = body != null ? optString(body, "error") : null;
						onDone.accept(new PushResult(false, code, 0, 0, err));
					}
				}
				catch (Exception ex)
				{
					log.debug("stat push parse error", ex);
					onDone.accept(new PushResult(false, -1, 0, 0, ex.getMessage()));
				}
			}
		});
	}

	public void postEvent(String baseUrl, String token, JsonObject event)
	{
		HttpUrl url = resolve(baseUrl, "api/events/" + token);
		if (url == null)
		{
			log.debug("postEvent: bad server URL {}", baseUrl);
			return;
		}
		post(url, event, new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("event push failed", e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful())
					{
						log.debug("event push HTTP {}", r.code());
					}
				}
			}
		});
	}

	public void pushClog(String baseUrl, String token, String name, Map<String, Object> snapshot)
	{
		HttpUrl url = resolve(baseUrl, "api/clog/" + token);
		if (url == null)
		{
			return;
		}
		JsonObject payload = gson.toJsonTree(snapshot).getAsJsonObject();
		payload.addProperty("playerName", name);
		post(url, payload, new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("clog push failed", e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				response.close();
			}
		});
	}

	public void pushAchievements(String baseUrl, String token, String name,
		JsonObject achievements, Consumer<Boolean> onDone)
	{
		HttpUrl url = resolve(baseUrl, "api/achievements/" + token);
		if (url == null)
		{
			onDone.accept(false);
			return;
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("playerName", name);
		payload.add("achievements", achievements);
		post(url, payload, new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("achievement push failed", e);
				onDone.accept(false);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				boolean ok = response.isSuccessful();
				response.close();
				onDone.accept(ok);
			}
		});
	}

	private void post(HttpUrl url, JsonObject body, Callback cb)
	{
		http.newCall(new Request.Builder().url(url).post(RequestBody.create(JSON, gson.toJson(body)))
			.build()).enqueue(cb);
	}

	@Nullable
	private HttpUrl resolve(String baseUrl, String path)
	{
		if (baseUrl == null || baseUrl.trim().isEmpty())
		{
			return null;
		}
		HttpUrl base = HttpUrl.parse(baseUrl.trim().replaceAll("/+$", ""));
		if (base == null)
		{
			return null;
		}
		HttpUrl.Builder b = base.newBuilder();
		for (String seg : path.split("/"))
		{
			if (!seg.isEmpty())
			{
				b.addPathSegment(seg);
			}
		}
		return b.build();
	}

	@Nullable
	private static String readCapped(Response r) throws IOException
	{
		ResponseBody body = r.body();
		if (body == null)
		{
			return null;
		}
		StringBuilder sb = new StringBuilder();
		char[] buf = new char[8192];
		try (Reader in = body.charStream())
		{
			int n;
			while ((n = in.read(buf)) != -1)
			{
				if (sb.length() + n > MAX_BODY_CHARS)
				{
					log.debug("server reply over {} chars; discarded", MAX_BODY_CHARS);
					return null;
				}
				sb.append(buf, 0, n);
			}
		}
		return sb.toString();
	}

	@Nullable
	private JsonObject parse(Response r) throws IOException
	{
		String s = readCapped(r);
		if (s == null || s.isEmpty())
		{
			return null;
		}
		try
		{
			return gson.fromJson(s, JsonObject.class);
		}
		catch (Exception e)
		{
			return null;
		}
	}

	@Nullable
	private static String optString(JsonObject o, String key)
	{
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
	}

	private static int optInt(JsonObject o, String key)
	{
		try
		{
			return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : 0;
		}
		catch (Exception e)
		{
			return 0;
		}
	}
}
