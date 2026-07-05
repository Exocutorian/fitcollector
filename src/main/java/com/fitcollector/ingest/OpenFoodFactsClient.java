package com.fitcollector.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Клієнт Open Food Facts — КБЖВ продукту за штрихкодом.
 *
 * Відповіді кешуються на диску (nutriments майже не змінюються), тому мережа
 * потрібна лише для нових штрихкодів. Запити тротляться під ліміт OFF
 * (100 запитів/хв на product API).
 */
@Component
public class OpenFoodFactsClient {

    private static final Logger log = LoggerFactory.getLogger(OpenFoodFactsClient.class);
    private static final double KJ_TO_KCAL = 4.184;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String userAgent;
    private final Path cacheDir;
    private final Duration cacheTtl;
    private final long throttleMillis;
    private volatile long lastRequestAt = 0;

    public OpenFoodFactsClient(
            ObjectMapper objectMapper,
            @Value("${fitcollector.off.base-url:https://world.openfoodfacts.org}") String baseUrl,
            @Value("${fitcollector.user-agent:FitCollector/0.2 (https://github.com/Exocutorian/fitcollector)}") String userAgent,
            @Value("${fitcollector.data-dir:./data}") String dataDir,
            @Value("${fitcollector.off.cache-ttl-days:7}") int cacheTtlDays,
            @Value("${fitcollector.off.throttle-millis:700}") long throttleMillis) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.userAgent = userAgent;
        this.cacheDir = Path.of(dataDir, "off-nutriments");
        this.cacheTtl = Duration.ofDays(cacheTtlDays);
        this.throttleMillis = throttleMillis;
    }

    /** КБЖВ на 100 г за штрихкодом; empty — якщо продукту немає або дані неповні. */
    public Optional<Macros> fetchMacros(String barcode) {
        Optional<String> cached = readCache(barcode);
        String body;
        if (cached.isPresent()) {
            body = cached.get();
        } else {
            try {
                body = fetchFromNetwork(barcode);
                writeCache(barcode, body);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.warn("OFF: не вдалося отримати {}: {}", barcode, e.getMessage());
                return Optional.empty();
            }
        }
        return parseMacros(body);
    }

    private String fetchFromNetwork(String barcode) throws IOException, InterruptedException {
        throttle();
        String url = baseUrl + "/api/v2/product/" + barcode + "?fields=nutriments";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", userAgent)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return "{\"status\":0}";
        }
        if (response.statusCode() != 200) {
            throw new IOException("OFF відповів " + response.statusCode());
        }
        return response.body();
    }

    private synchronized void throttle() throws InterruptedException {
        long wait = lastRequestAt + throttleMillis - System.currentTimeMillis();
        if (wait > 0) {
            Thread.sleep(wait);
        }
        lastRequestAt = System.currentTimeMillis();
    }

    Optional<Macros> parseMacros(String json) {
        try {
            OffResponse parsed = objectMapper.readValue(json, OffResponse.class);
            if (parsed.product() == null || parsed.product().nutriments() == null) {
                return Optional.empty();
            }
            Nutriments n = parsed.product().nutriments();
            Double kcal = n.energyKcal100g();
            if (kcal == null && n.energyKj100g() != null) {
                kcal = n.energyKj100g() / KJ_TO_KCAL;
            }
            if (kcal == null || n.proteins100g() == null) {
                return Optional.empty();
            }
            double fat = n.fat100g() == null ? 0 : n.fat100g();
            double carbs = n.carbohydrates100g() == null ? 0 : n.carbohydrates100g();
            return Optional.of(new Macros(kcal, n.proteins100g(), fat, carbs));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Optional<String> readCache(String barcode) {
        Path file = cacheDir.resolve(barcode + ".json");
        try {
            if (Files.exists(file)
                    && Files.getLastModifiedTime(file).toInstant().isAfter(Instant.now().minus(cacheTtl))) {
                return Optional.of(Files.readString(file));
            }
        } catch (IOException e) {
            log.debug("OFF cache read {}: {}", barcode, e.getMessage());
        }
        return Optional.empty();
    }

    private void writeCache(String barcode, String body) {
        try {
            Files.createDirectories(cacheDir);
            Files.writeString(cacheDir.resolve(barcode + ".json"), body);
        } catch (IOException e) {
            log.debug("OFF cache write {}: {}", barcode, e.getMessage());
        }
    }

    public record Macros(double kcalPer100g, double proteinPer100g, double fatPer100g, double carbsPer100g) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OffResponse(OffProduct product) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OffProduct(Nutriments nutriments) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Nutriments(
            @JsonProperty("energy-kcal_100g") Double energyKcal100g,
            @JsonProperty("energy_100g") Double energyKj100g,
            @JsonProperty("proteins_100g") Double proteins100g,
            @JsonProperty("fat_100g") Double fat100g,
            @JsonProperty("carbohydrates_100g") Double carbohydrates100g) {}
}
