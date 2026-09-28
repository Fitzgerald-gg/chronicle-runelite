/*
 * Copyright (c) 2026, Chronicle. BSD 2-Clause (see LICENSE).
 */
package chronicle;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
class GrindBook
{
	private static final int MIN_DRY_RATE = 100;
	private static final int MAX_ROWS = 20;
	private static final long MAX_KC = 100_000_000L;
	private static final int LEVEL_CAP = 99;
	private static final long MIN_RATE = 2;

	private final Gson gson;

	private volatile Map<String, Map<String, Integer>> drops;
	private volatile Map<String, SkillPet> skillPets;

	@RequiredArgsConstructor
	public static final class GrindRow
	{
		public final String boss;
		public final String item;
		public final long kc;
		public final long rate;
		public final double percentileDry;
	}

	@RequiredArgsConstructor
	static final class PetSource
	{
		final String boss;
		final long kc;
		final long rate;
	}

	@RequiredArgsConstructor
	static final class PetChase
	{
		final String pet;
		final long kc;
		final double percentileDry;
		final List<PetSource> sources;
		final String activity;
		final String unit;
		final long level;

		PetChase(String pet, long kc, double percentileDry, List<PetSource> sources)
		{
			this(pet, kc, percentileDry, sources, null, null, 0);
		}
	}

	@RequiredArgsConstructor
	private static final class SkillSource
	{
		final String counter;
		final String suffix;
		final String notSuffix;
		final List<String> minus;
		final String name;
		final long base;
	}

	@RequiredArgsConstructor
	private static final class SkillKill
	{
		final String kc;
		final List<String> orKc;
		final String name;
		final long base;
		final boolean flat;
	}

	private static final class SkillPet
	{
		final String skill;
		final String activity;
		final String unit;
		final boolean levelScaled;
		final List<SkillSource> sources = new ArrayList<>();
		final List<SkillKill> kills = new ArrayList<>();
		final Set<String> named = new HashSet<>();
		String diaryRegion;
		String diaryTier;

		SkillPet(JsonObject o)
		{
			skill = str(o, "skill");
			activity = str(o, "activity");
			unit = str(o, "unit");
			levelScaled = o.get("levelScaled").getAsBoolean();
			for (JsonElement el : array(o, "sources"))
			{
				JsonObject s = el.getAsJsonObject();
				SkillSource source = new SkillSource(str(s, "counter"), str(s, "suffix"), str(s, "notSuffix"),
					strings(s, "minus"), str(s, "name"), s.get("base").getAsLong());
				sources.add(source);
				if (source.counter != null)
				{
					named.add(source.counter);
				}
			}
			for (JsonElement el : array(o, "kills"))
			{
				JsonObject k = el.getAsJsonObject();
				kills.add(new SkillKill(str(k, "kc"), strings(k, "orKc"), str(k, "name"),
					k.get("base").getAsLong(), k.has("flat") && k.get("flat").getAsBoolean()));
			}
			JsonObject diary = obj(obj(o, "requires"), "diary");
			if (diary != null)
			{
				diaryRegion = Objects.toString(str(diary, "region"), "");
				diaryTier = str(diary, "tier");
			}
		}

		boolean unlocked(JsonObject achievements)
		{
			if (diaryRegion == null)
			{
				return true;
			}
			JsonObject region = obj(obj(achievements, "diaries"), diaryRegion);
			JsonElement done = region != null && diaryTier != null ? region.get(diaryTier) : null;
			return done != null && done.isJsonPrimitive() && done.getAsBoolean();
		}
	}

	private JsonObject resource(String name)
	{
		try (InputStream in = GrindBook.class.getResourceAsStream("/chronicle/" + name))
		{
			return gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
		}
		catch (Exception e)
		{
			log.debug("rate book {} unreadable", name, e);
			return new JsonObject();
		}
	}

	private Map<String, Map<String, Integer>> book()
	{
		if (drops == null)
		{
			Map<String, Map<String, Integer>> out = new HashMap<>();
			JsonObject bosses = obj(resource("osrs_clog_rates.json"), "drops");
			if (bosses != null)
			{
				for (Map.Entry<String, JsonElement> b : bosses.entrySet())
				{
					Map<String, Integer> items = new HashMap<>();
					b.getValue().getAsJsonObject().entrySet().forEach(it -> items.put(it.getKey(), it.getValue().getAsInt()));
					out.put(b.getKey(), items);
				}
			}
			drops = out;
		}
		return drops;
	}

	private Map<String, SkillPet> skillBook()
	{
		if (skillPets == null)
		{
			Map<String, SkillPet> out = new HashMap<>();
			JsonObject pets = obj(resource("osrs_skilling_pet_rates.json"), "pets");
			if (pets != null)
			{
				pets.entrySet().forEach(e -> out.put(e.getKey().toLowerCase(Locale.ROOT), new SkillPet(e.getValue().getAsJsonObject())));
			}
			skillPets = out;
		}
		return skillPets;
	}

	private static String norm(String s)
	{
		StringBuilder sb = new StringBuilder(s.length());
		for (char c : s.toLowerCase(Locale.ROOT).toCharArray())
		{
			if (Character.isLetterOrDigit(c))
			{
				sb.append(c);
			}
		}
		return sb.toString();
	}

	List<GrindRow> grinds(JsonObject clog, List<LocalStore.SourceRow> dropSources)
	{
		Map<String, Map<String, Integer>> rates = book();
		if (rates.isEmpty())
		{
			return new ArrayList<>();
		}
		Map<String, Long> kcByNorm = killCounts(clog, dropSources);
		Set<String> obtained = names(obj(clog, "clog_items"));
		obtained.addAll(looted(dropSources));
		Map<String, Set<String>> pageItems = new HashMap<>();
		JsonObject byCat = obj(clog, "by_cat");
		if (byCat != null)
		{
			for (Map.Entry<String, JsonElement> e : byCat.entrySet())
			{
				if (e.getValue().isJsonObject())
				{
					pageItems.put(norm(e.getKey()), names(e.getValue().getAsJsonObject()));
				}
			}
		}

		List<GrindRow> out = new ArrayList<>();
		for (Map.Entry<String, Map<String, Integer>> boss : rates.entrySet())
		{
			Long kc = kcByNorm.get(norm(boss.getKey()));
			if (kc == null || kc <= 0)
			{
				continue;
			}
			Set<String> page = pageFor(boss.getKey(), pageItems);
			for (Map.Entry<String, Integer> item : boss.getValue().entrySet())
			{
				int rate = item.getValue();
				String li = item.getKey().toLowerCase(Locale.ROOT);
				if (rate < MIN_DRY_RATE || obtained.contains(li) || (page != null && page.contains(li)))
				{
					continue;
				}
				double pct = (1.0 - Math.pow(1.0 - 1.0 / rate, kc)) * 100.0;
				out.add(new GrindRow(boss.getKey(), item.getKey(), kc, rate, Math.round(pct * 10.0) / 10.0));
			}
		}
		out.sort((a, b) ->
		{
			int byDry = Double.compare(b.percentileDry, a.percentileDry);
			return byDry != 0 ? byDry : Long.compare(b.rate, a.rate);
		});
		return out.size() > MAX_ROWS ? new ArrayList<>(out.subList(0, MAX_ROWS)) : out;
	}

	private static Set<String> pageFor(String key, Map<String, Set<String>> pageItems)
	{
		Set<String> page = pageItems.get(norm(key));
		int open = key.indexOf('(');
		int close = key.lastIndexOf(')');
		if (page == null && open >= 0 && close > open + 1)
		{
			return pageItems.get(norm(key.substring(open + 1, close)));
		}
		return page;
	}

	private static Map<String, Long> killCounts(JsonObject clog, List<LocalStore.SourceRow> dropSources)
	{
		Map<String, Long> kcByNorm = new HashMap<>();
		JsonObject kcs = obj(clog, "kcs");
		if (kcs != null)
		{
			for (Map.Entry<String, JsonElement> e : kcs.entrySet())
			{
				long v = safeLong(e.getValue());
				if (v > 0)
				{
					kcByNorm.merge(norm(e.getKey()), v, Math::max);
				}
			}
		}
		if (dropSources != null)
		{
			for (LocalStore.SourceRow sr : dropSources)
			{
				if (sr.kc > 0)
				{
					kcByNorm.merge(norm(sr.name), (long) sr.kc, Math::max);
				}
			}
		}
		return kcByNorm;
	}

	Map<String, PetChase> petChases(JsonObject clog, List<LocalStore.SourceRow> dropSources,
		Map<String, Long> counters, Map<String, long[]> skills, JsonObject achievements,
		Collection<String> pets)
	{
		Map<String, PetChase> out = new LinkedHashMap<>();
		if (pets == null || pets.isEmpty())
		{
			return out;
		}
		Map<String, Map<String, Integer>> rates = book();
		Map<String, SkillPet> skilling = skillBook();
		if (rates.isEmpty() && skilling.isEmpty())
		{
			return out;
		}
		Map<String, Long> kcByNorm = killCounts(clog, dropSources);
		Set<String> obtained = names(obj(clog, "clog_items"));
		JsonObject byCat = obj(clog, "by_cat");
		if (byCat != null)
		{
			for (Map.Entry<String, JsonElement> cat : byCat.entrySet())
			{
				if (cat.getValue().isJsonObject())
				{
					obtained.addAll(names(cat.getValue().getAsJsonObject()));
				}
			}
		}
		obtained.addAll(looted(dropSources));

		Map<String, List<PetSource>> bySource = new HashMap<>();
		for (Map.Entry<String, Map<String, Integer>> boss : rates.entrySet())
		{
			Long kc = kcByNorm.get(norm(boss.getKey()));
			if (kc == null || kc <= 0)
			{
				continue;
			}
			for (Map.Entry<String, Integer> item : boss.getValue().entrySet())
			{
				if (item.getValue() > 0)
				{
					bySource.computeIfAbsent(item.getKey().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
						.add(new PetSource(boss.getKey(), Math.min(kc, MAX_KC), item.getValue()));
				}
			}
		}
		for (String pet : pets)
		{
			if (pet == null)
			{
				continue;
			}
			String key = pet.toLowerCase(Locale.ROOT);
			if (obtained.contains(key))
			{
				continue;
			}
			SkillPet spec = skilling.get(key);
			List<PetSource> src = bySource.get(key);
			PetChase chase = null;
			if (spec != null)
			{
				chase = spec.unlocked(achievements) ? skillingChase(pet, spec, counters, skills, kcByNorm) : null;
			}
			else if (src != null)
			{
				src.sort((a, b) -> Long.compare(b.kc, a.kc));
				chase = chase(pet, src, null, null, 0);
			}
			if (chase != null)
			{
				out.put(key, chase);
			}
		}
		return out;
	}

	private static PetChase chase(String pet, List<PetSource> sources, String activity, String unit, long level)
	{
		double miss = 1.0;
		long kc = 0;
		for (PetSource s : sources)
		{
			miss *= Math.pow(1.0 - 1.0 / s.rate, s.kc);
			kc += s.kc;
		}
		sources.sort((a, b) -> Long.compare(b.kc, a.kc));
		double pct = Math.max(0.0, Math.min(100.0, (1.0 - miss) * 100.0));
		return new PetChase(pet, kc, Math.round(pct * 10.0) / 10.0, sources, activity, unit, level);
	}

	private static PetChase skillingChase(String pet, SkillPet spec, Map<String, Long> counters,
		Map<String, long[]> skills, Map<String, Long> kcByNorm)
	{
		long level = 0;
		if (spec.levelScaled)
		{
			long[] sheet = skills != null && spec.skill != null ? skills.get(spec.skill) : null;
			level = sheet != null && sheet.length > 0 ? sheet[0] : 0;
			if (level <= 0)
			{
				return null;
			}
			level = Math.min(level, LEVEL_CAP);
		}
		List<PetSource> sources = new ArrayList<>();
		for (SkillSource s : spec.sources)
		{
			addSource(sources, s.name, count(s, spec, counters), spec.levelScaled ? s.base - 25L * level : s.base);
		}
		for (SkillKill k : spec.kills)
		{
			long n = kcByNorm.getOrDefault(norm(k.kc), 0L);
			for (String alt : k.orKc)
			{
				n = Math.max(n, kcByNorm.getOrDefault(norm(alt), 0L));
			}
			addSource(sources, k.name, n, spec.levelScaled && !k.flat ? k.base - 25L * level : k.base);
		}
		return sources.isEmpty() ? null : chase(pet, sources, spec.activity, spec.unit, level);
	}

	private static void addSource(List<PetSource> out, String name, long n, long rate)
	{
		if (n > 0 && rate >= MIN_RATE)
		{
			out.add(new PetSource(name, Math.min(n, MAX_KC), rate));
		}
	}

	private static long count(SkillSource s, SkillPet spec, Map<String, Long> counters)
	{
		if (counters == null || counters.isEmpty())
		{
			return 0;
		}
		if (s.suffix != null)
		{
			long n = 0;
			for (Map.Entry<String, Long> e : counters.entrySet())
			{
				String k = e.getKey();
				if (k.endsWith(s.suffix) && !(s.notSuffix != null && k.endsWith(s.notSuffix)) && !spec.named.contains(k))
				{
					n += Math.max(0L, e.getValue() != null ? e.getValue() : 0L);
				}
			}
			return n;
		}
		long n = counters.getOrDefault(s.counter, 0L);
		for (String m : s.minus)
		{
			n -= counters.getOrDefault(m, 0L);
		}
		return Math.max(0L, n);
	}

	private static Set<String> names(JsonObject o)
	{
		Set<String> out = new HashSet<>();
		if (o != null)
		{
			o.keySet().forEach(k -> out.add(k.toLowerCase(Locale.ROOT)));
		}
		return out;
	}

	private static Set<String> looted(List<LocalStore.SourceRow> dropSources)
	{
		Set<String> out = new HashSet<>();
		if (dropSources != null)
		{
			for (LocalStore.SourceRow sr : dropSources)
			{
				if (sr.looted != null)
				{
					out.addAll(sr.looted);
				}
			}
		}
		return out;
	}

	private static JsonObject obj(JsonObject o, String key)
	{
		return o != null && o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : null;
	}

	private static Iterable<JsonElement> array(JsonObject o, String key)
	{
		return o.has(key) ? o.getAsJsonArray(key) : new ArrayList<>();
	}

	private static String str(JsonObject o, String key)
	{
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
	}

	private static List<String> strings(JsonObject o, String key)
	{
		List<String> out = new ArrayList<>();
		array(o, key).forEach(el -> out.add(el.getAsString()));
		return out;
	}

	private static long safeLong(JsonElement e)
	{
		try
		{
			return e != null && !e.isJsonNull() ? e.getAsLong() : 0;
		}
		catch (RuntimeException ex)
		{
			return 0;
		}
	}
}
