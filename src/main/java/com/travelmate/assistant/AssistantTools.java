package com.travelmate.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.travelmate.assistant.AssistantDtos.*;
import com.travelmate.common.ApiException;
import com.travelmate.repository.SpotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

@Service @RequiredArgsConstructor
public class AssistantTools {
    private final SpotRepository spots;
    private final KnowledgeService knowledge;
    private final RouteValidator routes;
    private final WalkingRouteService walking;
    public static class State {
        public Constraints constraints;
        public List<String> routeIds;
        public RouteCard route;
        public List<ComparisonRow> comparison=List.of();
        public final Map<String,Citation> citations=new LinkedHashMap<>();
        public State(Constraints c,List<String> ids){constraints=c;routeIds=new ArrayList<>(ids);}
    }
    public List<Map<String,Object>> definitions() {
        return List.of(
            def("search_spots","查询当前城市的候选景点。query可为空；不保证开放和亲子适宜性。",Map.of("query",string()),List.of("query")),
            def("get_spot_details","读取当前城市景点资料。",Map.of("spotId",string()),List.of("spotId")),
            def("get_walking_route","查询两个当前城市景点间真实步行时间；可能不可用。",Map.of("fromId",string(),"toId",string()),List.of("fromId","toId")),
            def("search_knowledge","检索当前城市的文档片段，用返回的引用ID标注回答。",Map.of("query",string()),List.of("query")),
            def("update_constraints","根据用户明确表达更新条件，省略字段保持原值。",Map.of(
                "durationMinutes",Map.of("type","integer","minimum",15,"maximum",480),"interests",string(),"companions",string()),List.of()),
            def("plan_route","提供完整的景点顺序，服务端校验时间并保存为当前路线。替换站点时保留未修改的站点。",Map.of("spotIds",ids()),List.of("spotIds")),
            def("compare_spots","按数据库字段生成2至3个景点的对比卡。",Map.of("spotIds",ids()),List.of("spotIds")));
    }
    private Map<String,Object> string(){return Map.of("type","string","maxLength",200);}
    private Map<String,Object> ids(){return Map.of("type","array","items",Map.of("type","string"),"minItems",1,"maxItems",6);}
    private Map<String,Object> def(String name,String description,Map<String,Object> properties,List<String> required){
        return Map.of("type","function","function",Map.of("name",name,"description",description,"parameters",
                Map.of("type","object","properties",properties,"required",required,"additionalProperties",false)));
    }
    public Object execute(String name,JsonNode a,State state) {
        if(!a.isObject())throw ApiException.badRequest("工具参数必须为对象");
        var c=state.constraints;
        return switch(name) {
            case "search_spots" -> {
                fields(a,Set.of("query"));String q=text(a,"query",true);
                yield spots.findByCityKey(c.cityKey()).stream().filter(s->q.isBlank() ||
                    (s.getName()+" "+Objects.toString(s.getTags(),"")+" "+Objects.toString(s.getCategory(),"")).contains(q))
                    .limit(12).map(s->Map.of("id",s.getId().toString(),"name",s.getName(),"tags",Objects.toString(s.getTags(),""),
                        "sourceStatus",Objects.toString(s.getSourceStatus(),"pending"))).toList();
            }
            case "get_spot_details" -> {
                fields(a,Set.of("spotId"));var s=routes.resolve(c.cityKey(),List.of(text(a,"spotId",false))).get(0);
                yield row(s);
            }
            case "get_walking_route" -> {
                fields(a,Set.of("fromId","toId"));var ss=routes.resolve(c.cityKey(),List.of(text(a,"fromId",false),text(a,"toId",false)));
                yield walking.walking(ss.get(0),ss.get(1));
            }
            case "search_knowledge" -> {
                fields(a,Set.of("query"));var found=knowledge.retrieve(c.cityKey(),text(a,"query",false),4);
                found.forEach(citation->state.citations.put(citation.id(),citation));yield found;
            }
            case "update_constraints" -> {
                fields(a,Set.of("durationMinutes","interests","companions"));
                int minutes=c.durationMinutes();
                if(a.has("durationMinutes")) {
                    if(!a.get("durationMinutes").isIntegralNumber()||!a.get("durationMinutes").canConvertToInt())throw ApiException.badRequest("时间必须为整数");
                    minutes=a.get("durationMinutes").asInt();
                    if(minutes<15||minutes>480)throw ApiException.badRequest("时间必须在15至480分钟之间");
                }
                String companions=a.has("companions")?text(a,"companions",true):c.companions();
                if(companions.length()>80)throw ApiException.badRequest("同行人描述过长");
                state.constraints=new Constraints(c.cityKey(),minutes,a.has("interests")?text(a,"interests",true):c.interests(),companions);
                // Cached card must never keep a stale budget after an update.
                state.route=null;yield state.constraints;
            }
            case "plan_route" -> {
                fields(a,Set.of("spotIds"));state.route=routes.validate(state.constraints,idList(a));
                state.routeIds=state.route.stops().stream().map(Stop::id).toList();yield state.route;
            }
            case "compare_spots" -> {
                fields(a,Set.of("spotIds"));var ids=idList(a);
                if(ids.size()<2||ids.size()>3)throw ApiException.badRequest("请选择2至3个景点对比");
                state.comparison=routes.resolve(c.cityKey(),ids).stream().map(this::row).toList();yield state.comparison;
            }
            default -> throw ApiException.badRequest("工具未注册");
        };
    }
    private ComparisonRow row(com.travelmate.domain.Spot s){return new ComparisonRow(s.getId().toString(),s.getName(),
        Objects.toString(s.getCategory(),"未知"),Objects.toString(s.getRecommendedDuration(),"未知"),
        Objects.toString(s.getOpenTime(),"未知"),Objects.toString(s.getTags(),""),Objects.toString(s.getSourceStatus(),"pending"));}
    private void fields(JsonNode a,Set<String> allowed){a.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw ApiException.badRequest("未知工具参数");});}
    private String text(JsonNode a,String key,boolean empty) {
        var v=a.get(key);if(v==null||!v.isTextual()||v.asText().length()>200||(!empty&&v.asText().isBlank()))throw ApiException.badRequest("工具参数无效："+key);
        return v.asText().trim();
    }
    private List<String> idList(JsonNode a){
        var ids=a.get("spotIds");if(ids==null||!ids.isArray()||ids.isEmpty()||ids.size()>6)throw ApiException.badRequest("景点列表无效");
        var result=new ArrayList<String>();for(var id:ids){if(!id.isTextual()||id.asText().length()>20)throw ApiException.badRequest("景点ID无效");result.add(id.asText());}return result;
    }
}
