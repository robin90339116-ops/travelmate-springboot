package com.travelmate.ai;

import com.travelmate.common.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * 通义千问(阿里百炼,OpenAI 兼容接口)客户端。支持文本与视觉多模态。
 */
@Slf4j
@Component
public class QwenClient {

    private final String apiKey;
    private final String baseUrl;
    private final String textModel;
    private final String visionModel;
    private final String provider;
    private final RestClient restClient;

    @org.springframework.beans.factory.annotation.Autowired
    public QwenClient(
            @Value("${app.ai.api-key:}") String apiKey,
            @Value("${app.ai.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${app.ai.text-model:qwen-plus}") String textModel,
            @Value("${app.ai.vision-model:qwen-vl-plus}") String visionModel,
            @Value("${app.ai.provider:bailian}") String provider) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.textModel = textModel;
        this.visionModel = visionModel;
        this.provider = provider;
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(30000);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    QwenClient(String apiKey,String baseUrl,String textModel,String visionModel,String provider,RestClient client) {
        this.apiKey=apiKey;this.baseUrl=baseUrl;this.textModel=textModel;this.visionModel=visionModel;this.provider=provider;this.restClient=client;
    }

    public String provider() {
        return provider;
    }

    public String textModel() {
        return textModel;
    }

    public String visionModel() {
        return visionModel;
    }

    @SuppressWarnings("unchecked")
    public String chat(String model, List<Map<String, Object>> messages) {
        if (!configured()) {
            throw ApiException.serviceUnavailable("AI Provider 未配置,请设置 app.ai.api-key(DASHSCOPE_API_KEY)");
        }
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", messages,
                "temperature", 0.4,
                "max_tokens", 1024);
        try {
            Map<String, Object> response = request(body);
            if (response == null) {
                throw ApiException.serviceUnavailable("AI Provider 返回为空");
            }
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw ApiException.serviceUnavailable("AI Provider 无有效结果");
            }
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            Object content = message == null ? null : message.get("content");
            if (content == null || content.toString().isBlank()) {
                throw ApiException.serviceUnavailable("AI Provider 返回内容为空");
            }
            return content.toString();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            Throwable root=e;
            while(root.getCause()!=null&&root.getCause()!=root)root=root.getCause();
            log.warn("调用 AI Provider 失败: {}, cause={}", e.getClass().getSimpleName(), root.getClass().getSimpleName());
            throw ApiException.serviceUnavailable("AI服务暂时不可用，请稍后重试");
        }
    }

    /** OpenAI-compatible tool response, preserving tool call IDs and arguments. */
    @SuppressWarnings("unchecked")
    public Map<String,Object> complete(List<Map<String,Object>> messages,
            List<Map<String,Object>> tools) {
        if (!configured()) throw ApiException.serviceUnavailable("AI Provider 未配置");
        var body = new java.util.LinkedHashMap<String,Object>();
        body.put("model", textModel); body.put("messages", messages);
        body.put("temperature", 0.2); body.put("max_tokens", 1800);
        if (!tools.isEmpty()) { body.put("tools", tools); body.put("tool_choice", "auto"); }
        try {
            var response = request(body);
            if (response == null || !(response.get("choices") instanceof List<?> choices) || choices.isEmpty()
                    || !(choices.get(0) instanceof Map<?,?> choice)
                    || !(choice.get("message") instanceof Map<?,?> message))
                throw ApiException.serviceUnavailable("模型返回结构无效");
            if (!message.containsKey("tool_calls") && !(message.get("content") instanceof String))
                throw ApiException.serviceUnavailable("模型没有返回内容或工具调用");
            return new java.util.LinkedHashMap<>((Map<String,Object>)message);
        } catch (ApiException e) { throw e; }
        catch (Exception e) {
            log.warn("工具模型调用失败: {} / {}",e.getClass().getSimpleName(),e.getCause()==null?"none":e.getCause().getClass().getSimpleName());
            throw ApiException.serviceUnavailable("AI服务暂时不可用，请稍后重试");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> request(Map<String,Object> body) {
        for(int attempt=0;attempt<3;attempt++) {
            try { return restClient.post()
                    .uri(baseUrl.replaceAll("/+$", "") + "/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            } catch(org.springframework.web.client.RestClientResponseException e) {
                if(attempt==2 || !(e.getStatusCode().value()==429 || e.getStatusCode().is5xxServerError()))throw e;
                try { Thread.sleep(200L << attempt); }
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw ApiException.serviceUnavailable("请求已中断");}
            }
        }
        throw ApiException.serviceUnavailable("AI服务重试失败");
    }
}
