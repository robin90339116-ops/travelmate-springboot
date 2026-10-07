package com.travelmate.map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class PlaceEnricherTest {

    static LiveLocationService.Poi poi(Map<String, String> facts) {
        return new LiveLocationService.Poi("node/1", "故宫", "景山前街4号", "museum", "116.39,39.91",
                "https://www.openstreetmap.org/node/1", facts);
    }

    static LiveLocationService.Context context(String knowledge, List<LiveLocationService.Source> sources) {
        return new LiveLocationService.Context("t", 1, new LiveLocationService.Position(116, 39, 5, 0), "", "",
                List.of(), "node/1", knowledge, sources, Long.MAX_VALUE);
    }

    @Test
    void osmTagsAreCopiedFromAllowListOnly() throws Exception {
        var tags = new ObjectMapper().readTree("""
                {"description:zh":"明清两代皇宫","description":"Palace","opening_hours":"Tu-Su 08:30-17:00",
                 "wikipedia:zh":"故宫","phone":"+86 10 0000","addr:full":"x"}""");
        var facts = LiveLocationService.osmFacts(tags);
        assertEquals("明清两代皇宫", facts.get("description"), "prefers the Chinese tag");
        assertEquals("zh:故宫", facts.get("wikipedia"), "wikipedia:zh title gets a language prefix");
        assertEquals("Tu-Su 08:30-17:00", facts.get("opening_hours"));
        assertFalse(facts.containsKey("phone"), "tags outside the allow-list are dropped");
    }

    @Test
    void factsCarrySourcesAndIntroPrefersDescription() {
        var enricher = new PlaceEnricher(RestClient.create(), false);
        var e = enricher.enrich(poi(Map.of("description", "明清两代皇宫", "opening_hours", "Tu-Su 08:30-17:00")),
                context("故宫始建于明永乐年间。", List.of(new LiveLocationService.Source("官网", "https://www.dpm.org.cn/"))));
        assertEquals("明清两代皇宫", e.intro());
        assertTrue(e.openTime().contains("待核实"));
        assertTrue(e.facts().stream().anyMatch(f -> f.startsWith("OpenStreetMap 描述：")));
        assertTrue(e.facts().stream().anyMatch(f -> f.startsWith("联网检索摘要") && f.contains("https://www.dpm.org.cn/")));
        assertEquals("OpenStreetMap · 联网检索", e.sourceName());
    }

    @Test
    void wikipediaSummaryIsFetchedWithoutApiKey() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://zh.wikipedia.org/api/rest_v1/page/summary/%E6%95%85%E5%AE%AB"))
                .andExpect(header("Accept-Language", "zh-CN"))
                .andRespond(withSuccess("""
                        {"type":"standard","extract":"北京故宫是中国明清两代的皇家宫殿，旧称紫禁城。",
                         "content_urls":{"desktop":{"page":"https://zh.wikipedia.org/wiki/故宫"}}}""",
                        MediaType.APPLICATION_JSON));
        var e = new PlaceEnricher(builder.build(), true).enrich(poi(Map.of("wikipedia", "zh:故宫")), null);
        server.verify();
        assertTrue(e.facts().stream().anyMatch(f -> f.startsWith("维基百科摘要") && f.contains("紫禁城")));
        assertTrue(e.intro().contains("紫禁城"), "without an OSM description the summary becomes the intro");
        assertEquals("OpenStreetMap · 维基百科", e.sourceName());
    }

    @Test
    void wikipediaFailureDegradesToOsmFacts() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://zh.wikipedia.org/"))).andRespond(withServerError());
        var e = new PlaceEnricher(builder.build(), true).enrich(poi(Map.of("wikipedia", "zh:故宫")), null);
        assertTrue(e.facts().isEmpty());
        assertEquals("OpenStreetMap", e.sourceName());
        assertTrue(e.intro().startsWith("社区地图收录地点"));
    }

    @Test
    void disambiguationAndMalformedTagsAreIgnored() throws Exception {
        var json = new ObjectMapper();
        assertTrue(PlaceEnricher.parseWiki(json.readTree("{\"type\":\"disambiguation\",\"extract\":\"多个含义的条目列表\"}")).isEmpty());
        assertTrue(new PlaceEnricher(RestClient.create(), true).wikipedia("not a wiki tag").isEmpty());
    }
}
