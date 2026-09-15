package com.travelmate.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmate.repository.SpotRepository;
import com.travelmate.assistant.AssistantDtos.Citation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Small-corpus lexical RAG: Chinese bigram / Latin word TF-IDF retrieval, no embedding claim. */
@Service
public class KnowledgeService {
    public record Document(String id, String cityKey, String title, String text, String sourceUrl,
            String updatedAt, String sourceStatus) {}
    private record Chunk(String city, Citation citation, Map<String,Integer> terms) {}
    private final List<Chunk> documents = new ArrayList<>();
    private final SpotRepository spots;
    public KnowledgeService(SpotRepository spots, ObjectMapper json,
            @Value("${app.assistant.knowledge-location:classpath*:knowledge/*.json}") String location) throws Exception {
        this.spots = spots;
        for (var resource : new PathMatchingResourcePatternResolver().getResources(location)) {
            try (var input = resource.getInputStream()) {
                for (Document d : json.readValue(input, Document[].class)) add(d);
            }
        }
    }
    private void add(Document d) throws Exception {
        if (d.id()==null || d.cityKey()==null || d.text()==null || d.title()==null)
            throw new IllegalArgumentException("知识文档缺少必填字段");
        String clean = d.text().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        if (clean.length()>100000) throw new IllegalArgumentException("知识文档超过100000字符");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(clean.getBytes(StandardCharsets.UTF_8))).substring(0,12);
        String url = Objects.toString(d.sourceUrl(), "");
        if (!url.isEmpty() && !url.startsWith("https://")) throw new IllegalArgumentException("来源必须为HTTPS地址");
        for (int start=0, index=0; start<clean.length(); start+=420,index++) {
            String text = clean.substring(start, Math.min(clean.length(),start+500));
            var citation = new Citation(d.id()+"-"+hash+"-"+index,d.title(),url,
                    Objects.toString(d.updatedAt(),"unknown"),Objects.toString(d.sourceStatus(),"pending"),text);
            documents.add(new Chunk(d.cityKey(),citation,terms(d.title()+" "+text)));
        }
    }
    public List<Citation> retrieve(String city, String query, int limit) {
        var corpus = new ArrayList<>(documents.stream().filter(c->c.city().equals(city)).toList());
        // Existing catalog is explicitly identified as catalog/demo material, not verified documents.
        for (var s : spots.findByCityKey(city)) {
            String text = s.getName()+"；"+Objects.toString(s.getIntro(),"")+"；"+Objects.toString(s.getHighlight(),"")
                    +"；标签："+Objects.toString(s.getTags(),"")+"；开放时间："+Objects.toString(s.getOpenTime(),"未知");
            var c = new Citation("catalog-"+s.getId(),s.getName(),"","unknown",
                    Objects.toString(s.getSourceStatus(),"pending"),text);
            corpus.add(new Chunk(city,c,terms(text)));
        }
        var queryTerms = terms(query);
        Map<String,Integer> df = new HashMap<>();
        corpus.forEach(c->c.terms().keySet().forEach(t->df.merge(t,1,Integer::sum)));
        record Hit(Citation citation, double score) {}
        return corpus.stream().map(c->{
            double score=0;
            for (String t : queryTerms.keySet()) if (c.terms().containsKey(t))
                score+=(1+Math.log(c.terms().get(t)))*Math.log(1+(double)corpus.size()/df.get(t));
            return new Hit(c.citation(),score/Math.sqrt(Math.max(1,c.terms().size())));
        }).filter(h->h.score()>0).sorted(Comparator.comparingDouble(Hit::score).reversed()
                .thenComparing(h->h.citation().id())).limit(Math.min(5,Math.max(1,limit))).map(Hit::citation).toList();
    }
    static Map<String,Integer> terms(String input) {
        Map<String,Integer> result=new HashMap<>();
        var matcher=java.util.regex.Pattern.compile("[\\p{IsHan}]+|[a-z0-9]+").matcher(input.toLowerCase(Locale.ROOT));
        while(matcher.find()) {
            String token=matcher.group();
            if (Character.UnicodeScript.of(token.charAt(0))==Character.UnicodeScript.HAN && token.length()>1) {
                for(int i=0;i<token.length()-1;i++)result.merge(token.substring(i,i+2),1,Integer::sum);
            } else result.merge(token,1,Integer::sum);
        }
        return result;
    }
}
