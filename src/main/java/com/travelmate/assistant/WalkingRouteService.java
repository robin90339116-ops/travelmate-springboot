package com.travelmate.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.travelmate.domain.Spot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class WalkingRouteService {
    public record Leg(String from, String to, Integer minutes, Integer meters, String status) {}
    private final String key;
    private final RestClient client;
    public WalkingRouteService(@Value("${app.map.amap-web-key:}") String key) {
        this.key=key;
        var factory=new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000); factory.setReadTimeout(4000);
        client=RestClient.builder().requestFactory(factory).build();
    }
    public Leg walking(Spot from, Spot to) {
        String a=from.getId().toString(), b=to.getId().toString();
        if(key.isBlank() || !coordinates(from) || !coordinates(to))return new Leg(a,b,null,null,"unavailable");
        try {
            JsonNode response=client.get().uri(builder->builder.scheme("https").host("restapi.amap.com")
                    .path("/v5/direction/walking").queryParam("key",key).queryParam("show_fields","cost")
                    .queryParam("origin",from.getLongitude()+","+from.getLatitude())
                    .queryParam("destination",to.getLongitude()+","+to.getLatitude()).build())
                    .retrieve().body(JsonNode.class);
            if(response==null || !response.path("status").asText().equals("1"))throw new IllegalArgumentException();
            var path=response.path("route").path("paths").path(0);
            double seconds=Double.parseDouble(path.path("cost").path("duration").asText());
            double meters=Double.parseDouble(path.path("distance").asText());
            if(!Double.isFinite(seconds)||!Double.isFinite(meters)||seconds<0||seconds>86400||meters<0||meters>100000)
                throw new IllegalArgumentException();
            return new Leg(a,b,(int)Math.ceil(seconds/60),(int)Math.ceil(meters),"verified");
        } catch(Exception e) { return new Leg(a,b,null,null,"unavailable"); }
    }
    private boolean coordinates(Spot s) {
        return s.getLongitude()!=null && s.getLatitude()!=null && Double.isFinite(s.getLongitude())
                && Double.isFinite(s.getLatitude()) && Math.abs(s.getLongitude())<=180 && Math.abs(s.getLatitude())<=90;
    }
}
