package com.autotrading.market;

import com.autotrading.entity.StockAnalysisRecord;
import com.autotrading.repository.StockAnalysisRecordRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class ExternalAnalysisService {

    private static final Logger logger = LoggerFactory.getLogger(ExternalAnalysisService.class);
    
    @Value("${ai.analysis.api.url:http://host.docker.internal:9000/api/analyze}")
    private String apiUrl;

    private final StockAnalysisRecordRepository repository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public ExternalAnalysisService(StockAnalysisRecordRepository repository) {
        this.repository = repository;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(30000);
        this.restTemplate = new RestTemplate(factory);
        this.objectMapper = new ObjectMapper();
    }

    public Map<String, Object> analyzeStock(int market, String code) {
        String tradeDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String stockKey = market + "." + code;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stockKey", stockKey);
        result.put("market", market);
        result.put("code", code);
        result.put("tradeDate", tradeDate);

        try {
            // Prepare request body
            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("ticker", code);
            requestBody.put("trade_date", tradeDate);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, String>> entity = new HttpEntity<>(requestBody, headers);

            logger.info("Calling AI Analysis API: {} with ticker: {}", apiUrl, code);
            
            // Call external API
            ResponseEntity<String> response = restTemplate.exchange(
                    apiUrl,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                String rawResult = response.getBody();
                result.put("success", true);
                result.put("rawResult", rawResult);

                // Parse and structure the result
                JsonNode root = objectMapper.readTree(rawResult);

                // Extract structured fields
                String rating = root.has("rating") ? root.get("rating").asText() : "HOLD";
                double targetPrice = root.has("targetPrice") ? root.get("targetPrice").asDouble() : 0.0;
                String summary = root.has("summary") ? root.get("summary").asText() : "";

                result.put("rating", rating);
                result.put("targetPrice", targetPrice);
                result.put("summary", summary);

                // Extract additional analysis sections if available
                if (root.has("analysis")) {
                    result.put("analysis", parseJsonNode(root.get("analysis")));
                }
                if (root.has("riskFactors")) {
                    result.put("riskFactors", parseJsonNode(root.get("riskFactors")));
                }
                if (root.has("keyMetrics")) {
                    result.put("keyMetrics", parseJsonNode(root.get("keyMetrics")));
                }

                // Save to database
                StockAnalysisRecord record = new StockAnalysisRecord(
                        stockKey,
                        String.valueOf(market),
                        code,
                        tradeDate,
                        rating,
                        targetPrice,
                        summary,
                        rawResult
                );
                repository.save(record);
                result.put("recordId", record.getId());

            } else {
                result.put("success", false);
                result.put("error", "API returned status: " + response.getStatusCode());
            }

        } catch (Exception e) {
            logger.error("Failed to call external analysis API for {}.{}: {}", market, code, e.getMessage());
            result.put("success", false);
            result.put("error", "External API unavailable: " + e.getMessage());
        }

        return result;
    }

    public List<Map<String, Object>> getHistory(int market, String code) {
        List<StockAnalysisRecord> records = repository.findByMarketAndCodeOrderByCreatedAtDesc(
                String.valueOf(market), code
        );

        List<Map<String, Object>> history = new ArrayList<>();
        for (StockAnalysisRecord record : records) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", record.getId());
            item.put("tradeDate", record.getTradeDate());
            item.put("rating", record.getRating());
            item.put("targetPrice", record.getTargetPrice());
            item.put("summary", record.getSummary());
            item.put("createdAt", record.getCreatedAt().toString());
            history.add(item);
        }

        return history;
    }

    private Object parseJsonNode(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                map.put(field.getKey(), parseJsonNode(field.getValue()));
            }
            return map;
        } else if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode item : node) {
                list.add(parseJsonNode(item));
            }
            return list;
        } else if (node.isNumber()) {
            return node.asDouble();
        } else if (node.isBoolean()) {
            return node.asBoolean();
        } else {
            return node.asText();
        }
    }
}
