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
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
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
public class ChronicleApiClient
{
	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

	private final OkHttpClient http;
	private final Gson gson;

	@Setter
	private volatile long accountHash = -1L;

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

	public void pushStats(String baseUrl, String token, String name, Map<String, Integer> stats,
		@Nullable String accountType, @Nullable JsonObject skills, Runnable onDone)
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("playerName", name);
		long h = accountHash;
		if (h != -1L)
		{
			payload.addProperty("accountHash", String.valueOf(h));
		}
		if (accountType != null && !accountType.isEmpty())
		{
			payload.addProperty("accountType", accountType);
		}
		JsonObject statsObj = new JsonObject();
		stats.forEach((k, v) ->
		{
			if (v != null)
			{
				statsObj.addProperty(k, v);
			}
		});
		payload.add("stats", statsObj);
		if (skills != null && skills.size() > 0)
		{
			payload.add("skills", skills);
		}
		post(baseUrl, "counters", token, payload, ok -> onDone.run());
	}

	public void postEvent(String baseUrl, String token, JsonObject event)
	{
		post(baseUrl, "events", token, event, ok -> { });
	}

	public void pushClog(String baseUrl, String token, String name, Map<String, Object> snapshot)
	{
		JsonObject payload = gson.toJsonTree(snapshot).getAsJsonObject();
		payload.addProperty("playerName", name);
		post(baseUrl, "clog", token, payload, ok -> { });
	}

	public void pushAchievements(String baseUrl, String token, String name,
		JsonObject achievements, Consumer<Boolean> onDone)
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("playerName", name);
		payload.add("achievements", achievements);
		post(baseUrl, "achievements", token, payload, onDone);
	}

	private void post(String baseUrl, String endpoint, String token, JsonObject body, Consumer<Boolean> onDone)
	{
		HttpUrl url = resolve(baseUrl, endpoint, token);
		if (url == null)
		{
			onDone.accept(false);
			return;
		}
		Request request = new Request.Builder().url(url).post(RequestBody.create(JSON, gson.toJson(body))).build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("{} push failed", endpoint, e);
				onDone.accept(false);
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
				onDone.accept(ok);
			}
		});
	}

	@Nullable
	private static HttpUrl resolve(String baseUrl, String endpoint, String token)
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
		HttpUrl.Builder b = base.newBuilder().addPathSegment("api").addPathSegment(endpoint);
		for (String seg : token.split("/"))
		{
			if (!seg.isEmpty())
			{
				b.addPathSegment(seg);
			}
		}
		return b.build();
	}
}
