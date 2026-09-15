package com.travelmate.assistant;

import com.travelmate.assistant.AssistantDtos.*;
import com.travelmate.common.ApiException;
import com.travelmate.domain.Spot;
import com.travelmate.repository.SpotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.Pattern;

@Service @RequiredArgsConstructor
public class RouteValidator {
    private final SpotRepository spots;
    private final WalkingRouteService walking;
    public List<Spot> resolve(String city, List<String> ids) {
        if(ids==null || ids.isEmpty() || ids.size()>6 || new HashSet<>(ids).size()!=ids.size())
            throw ApiException.badRequest("路线必须包含1至6个不同景点");
        List<Spot> result=new ArrayList<>();
        for(String id:ids) {
            Spot s;
            try{s=spots.findById(Long.parseLong(id)).orElseThrow(()->ApiException.badRequest("景点不存在"));}
            catch(NumberFormatException e){throw ApiException.badRequest("景点ID无效");}
            if(!city.equals(s.getCityKey()))throw ApiException.badRequest("景点不在当前城市");
            result.add(s);
        }
        return result;
    }
    public RouteCard validate(Constraints constraints, List<String> ids) {
        var selected=resolve(constraints.cityKey(),ids);
        var removed=new ArrayList<String>();
        var warnings=new ArrayList<String>();
        var stops=new ArrayList<Stop>();
        int total=0;
        boolean known=true;
        Spot previous=null;
        for(var s:selected) {
            Integer stay=stayMinutes(s.getRecommendedDuration());
            Integer travel=0;
            if(previous!=null)travel=walking.walking(previous,s).minutes();
            if(stay==null || travel==null)known=false;
            int next=total+(stay==null?0:stay)+(travel==null?0:travel);
            if(known && next>constraints.durationMinutes()) {
                removed.add(s.getId().toString());
                // Keep a feasible prefix; no hidden reordering or invented travel times.
                continue;
            }
            total=next;
            stops.add(new Stop(s.getId().toString(),s.getName(),stay,
                    Objects.toString(s.getOpenTime(),"未知"),Objects.toString(s.getSourceStatus(),"pending")));
            previous=s;
        }
        if(!known)warnings.add("步行或停留时间资料缺失，无法确认符合时间预算。");
        if(!removed.isEmpty())warnings.add("已移除超出时间预算的景点，请查看保留的路线。");
        if(stops.stream().anyMatch(s->!"verified".equals(s.sourceStatus())))warnings.add("部分景点资料为演示或待核实内容。");
        warnings.add("时间仅含景点停留和站间步行，不含出发地、返程、排队；开放时间与亲子适宜性需另行确认。");
        String status=stops.isEmpty()?"infeasible":known?"within_budget":"unverified";
        return new RouteCard(stops,known?total:null,constraints.durationMinutes(),status,warnings,removed);
    }
    static Integer stayMinutes(String raw) {
        if(raw==null)return null;
        var m=Pattern.compile("^\\s*(\\d{1,3})\\s*(?:分钟|min)\\s*$").matcher(raw);
        if(!m.matches())return null;
        int n=Integer.parseInt(m.group(1));return n>0&&n<=480?n:null;
    }
}
