package com.travelmate.map;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Builds the facts an arbitrary real place is explained from.
 * Sources, in order: public OpenStreetMap tags, the Wikipedia summary linked by the OSM
 * {@code wikipedia} tag (no API key), and the user's DeepSeek web lookup if they ran one.
 * Every fact carries its source and is never presented as verified. External failures
 * degrade to fewer facts; they never block importing the place.
 */
@Slf4j
@Service
public class PlaceEnricher {

    public record Wiki(String extract, String url) {}

    public record Enrichment(String intro, List<String> facts, String sourceName, String openTime) {}

    private static final Pattern WIKI_TAG = Pattern.compile("^([a-z]{2,3}(?:-[a-z]+)?):(.{1,200})$");
    private static final Map<String, String> OSM_LABELS = new LinkedHashMap<>();
    static {
        OSM_LABELS.put("description", "OpenStreetMap 描述");
        OSM_LABELS.put("opening_hours", "OpenStreetMap 开放时间（社区数据，可能过期）");
        OSM_LABELS.put("heritage", "OpenStreetMap 文物保护等级标记");
        OSM_LABELS.put("start_date", "OpenStreetMap 建成或始建时间");
        OSM_LABELS.put("architect", "OpenStreetMap 建筑师");
        OSM_LABELS.put("website", "OpenStreetMap 标注的网站");
    }

    private final RestClient client;
    private final boolean wikipediaEnabled;

    @Autowired
    public PlaceEnricher(@Value("${app.knowledge.wikipedia-enabled:true}") boolean wikipediaEnabled) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(4000);
        factory.setReadTimeout(6000);
        this.client = RestClient.builder().requestFactory(factory).build();
        this.wikipediaEnabled = wikipediaEnabled;
    }

    PlaceEnricher(RestClient client, boolean wikipediaEnabled) {
        this.client = client;
        this.wikipediaEnabled = wikipediaEnabled;
    }

    public Enrichment enrich(LiveLocationService.Poi poi, LiveLocationService.Context context) {
        Map<String, String> tags = poi.facts();
        List<String> facts = new ArrayList<>(osmFacts(tags));
        Set<String> sources = new LinkedHashSet<>(List.of("OpenStreetMap"));

        Wiki wiki = wikipediaEnabled && tags.containsKey("wikipedia") ? wikipedia(tags.get("wikipedia")).orElse(null) : null;
        if (wiki != null) {
            facts.add("维基百科摘要（社区编辑，未经本服务核验；来源 " + wiki.url() + "）：" + wiki.extract());
            sources.add("维基百科");
        }
        if (context != null && context.knowledge() != null && !context.knowledge().isBlank()) {
            String urls = context.sources().stream().limit(3).map(LiveLocationService.Source::url)
                    .reduce((a, b) -> a + "、" + b).orElse("无");
            facts.add("联网检索摘要（DeepSeek，未经独立核验；来源 " + urls + "）：" + limit(context.knowledge(), 800));
            sources.add("联网检索");
        }

        String intro = tags.containsKey("description") ? tags.get("description")
                : wiki != null ? limit(wiki.extract(), 200)
                : "社区地图收录地点；地址：" + poi.address();
        String openTime = tags.containsKey("opening_hours")
                ? limit("OSM：" + tags.get("opening_hours") + "（待核实）", 64) : "待核实";
        return new Enrichment(limit(intro, 512), facts, limit(String.join(" · ", sources), 64), openTime);
    }

    static List<String> osmFacts(Map<String, String> tags) {
        List<String> out = new ArrayList<>();
        OSM_LABELS.forEach((key, label) -> {
            String value = tags.get(key);
            if (value != null && !value.isBlank()) out.add(label + "：" + value);
        });
        return out;
    }

    Optional<Wiki> wikipedia(String tag) {
        var m = WIKI_TAG.matcher(tag.trim());
        if (!m.matches()) return Optional.empty();
        String lang = m.group(1), title = m.group(2).trim().replace(' ', '_');
        try {
            URI uri = URI.create("https://" + lang + ".wikipedia.org/api/rest_v1/page/summary/"
                    + URLEncoder.encode(title, StandardCharsets.UTF_8).replace("+", "%20"));
            JsonNode data = client.get().uri(uri)
                    .header("User-Agent", "TravelMate/1.0 (portfolio prototype; place summaries)")
                    .header("Accept-Language", "zh".equals(lang) ? "zh-CN" : lang)
                    .retrieve().body(JsonNode.class);
            return parseWiki(data);
        } catch (Exception e) {
            log.info("Wikipedia summary unavailable: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    static Optional<Wiki> parseWiki(JsonNode data) {
        if (data == null || "disambiguation".equals(data.path("type").asText())) return Optional.empty();
        String extract = data.path("extract").asText("").trim();
        String url = data.path("content_urls").path("desktop").path("page").asText("");
        if (extract.length() < 10 || !url.startsWith("https://")) return Optional.empty();
        return Optional.of(new Wiki(limit(extract, 900), url));
    }

    static String limit(String s, int n) {
        return s == null ? "" : s.substring(0, Math.min(s.length(), n));
    }
}
