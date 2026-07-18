package com.fitcollector.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitcollector.ingest.OpenFoodFactsClient.Macros;
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
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Індекс продуктів Open Food Facts для домапування КБЖВ до продуктів магазину,
 * у якого немає ні складу, ні штрихкодів у лістингу (Biedronka).
 *
 * Тягне з OFF найпопулярніші польські продукти + все з тегом магазину, кешує на диску,
 * і матчить по (вага упаковки, бренд, токени назви).
 */
@Component
public class OffMatchIndex {

    private static final Logger log = LoggerFactory.getLogger(OffMatchIndex.class);
    private static final int PAGE_SIZE = 100;
    /** Ліміт OFF на search API — 10 запитів/хв. */
    private static final long SEARCH_THROTTLE_MILLIS = 6_500;

    private static final Set<String> STOP_TOKENS = Set.of(
            "g", "kg", "l", "ml", "szt", "x", "typu", "smak", "o", "z", "w", "i", "do", "na");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String userAgent;
    private final Path cacheFile;
    private final Duration cacheTtl;
    private final int popularPages;
    private final int storePages;

    private volatile List<Entry> entries = List.of();
    private volatile Map<Long, List<Entry>> byGrams = Map.of();

    public OffMatchIndex(
            ObjectMapper objectMapper,
            @Value("${fitcollector.off.search-base-url:https://pl.openfoodfacts.org}") String baseUrl,
            @Value("${fitcollector.user-agent:FitCollector/0.3 (https://github.com/Exocutorian/fitcollector)}") String userAgent,
            @Value("${fitcollector.data-dir:./data}") String dataDir,
            @Value("${fitcollector.off.index-ttl-days:7}") int cacheTtlDays,
            @Value("${fitcollector.off.index-popular-pages:10}") int popularPages,
            @Value("${fitcollector.off.index-store-pages:10}") int storePages) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.userAgent = userAgent;
        this.cacheFile = Path.of(dataDir, "off-index.json");
        this.cacheTtl = Duration.ofDays(cacheTtlDays);
        this.popularPages = popularPages;
        this.storePages = storePages;
    }

    /** Завантажує індекс (кеш або мережа) і готує його до матчингу. */
    public void ensureLoaded() throws IOException, InterruptedException {
        if (!entries.isEmpty()) {
            return;
        }
        List<Entry> loaded = loadCache();
        if (loaded == null) {
            loaded = fetchFromNetwork();
            saveCache(loaded);
        }
        index(loaded);
    }

    public int size() {
        return entries.size();
    }

    /**
     * Шукає в індексі продукт з тією ж вагою упаковки та схожою назвою/брендом.
     */
    public Optional<Entry> match(String name, String brand, double packageGrams) {
        List<Entry> candidates = new ArrayList<>();
        long base = Math.round(packageGrams);
        for (long grams = base - 5; grams <= base + 5; grams++) {
            candidates.addAll(byGrams.getOrDefault(grams, List.of()));
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        Set<String> brandTokens = tokens(brand);
        Set<String> nameTokens = tokens(name);
        nameTokens.removeAll(brandTokens);
        if (nameTokens.isEmpty()) {
            nameTokens = tokens(name);
        }

        Entry best = null;
        double bestScore = 0;
        for (Entry e : candidates) {
            Set<String> entryName = new HashSet<>(e.nameTokens());
            entryName.removeAll(e.brandTokens());
            if (entryName.isEmpty()) {
                entryName = new HashSet<>(e.nameTokens());
            }
            Set<String> intersection = new HashSet<>(nameTokens);
            intersection.retainAll(entryName);
            Set<String> union = new HashSet<>(nameTokens);
            union.addAll(entryName);
            double score = union.isEmpty() ? 0 : (double) intersection.size() / union.size();

            boolean brandMatches = !brandTokens.isEmpty()
                    && !java.util.Collections.disjoint(brandTokens, e.brandTokens());
            double threshold = brandMatches ? 0.5 : 0.72;
            if (score >= threshold && score > bestScore) {
                best = e;
                bestScore = score;
            }
        }
        return Optional.ofNullable(best);
    }

    private void index(List<Entry> loaded) {
        Map<Long, List<Entry>> map = new HashMap<>();
        for (Entry e : loaded) {
            map.computeIfAbsent(Math.round(e.grams()), k -> new ArrayList<>()).add(e);
        }
        this.entries = loaded;
        this.byGrams = map;
        log.info("OFF-індекс: {} продуктів готово до матчингу", loaded.size());
    }

    private List<Entry> fetchFromNetwork() throws IOException, InterruptedException {
        Map<String, Entry> byCode = new HashMap<>();
        // найпопулярніші польські продукти + все, що позначене магазином Biedronka
        collect(byCode, "&sort_by=unique_scans_n", popularPages);
        collect(byCode, "&stores_tags=biedronka&sort_by=unique_scans_n", storePages);
        log.info("OFF-індекс: зібрано {} придатних продуктів", byCode.size());
        return new ArrayList<>(byCode.values());
    }

    private void collect(Map<String, Entry> byCode, String filter, int pages)
            throws IOException, InterruptedException {
        // product_quantity через search API не віддається — беремо текстове quantity ("420 g")
        String fields = "code,product_name,product_name_pl,brands,quantity,image_front_url,nutriments";
        for (int page = 1; page <= pages; page++) {
            String url = baseUrl + "/api/v2/search?page_size=" + PAGE_SIZE + "&page=" + page
                    + "&fields=" + fields + filter;
            SearchPage parsed = null;
            // OFF search буває флейковим (503/401) — одна повторна спроба з паузою
            for (int attempt = 1; attempt <= 2 && parsed == null; attempt++) {
                try {
                    parsed = objectMapper.readValue(fetchJson(url), SearchPage.class);
                } catch (IOException e) {
                    log.warn("OFF-індекс: сторінка {} ({}) спроба {}: {}", page, filter, attempt, e.getMessage());
                    Thread.sleep(SEARCH_THROTTLE_MILLIS * attempt);
                }
            }
            if (parsed == null) {
                continue;
            }
            int usable = 0;
            for (SearchProduct p : parsed.products()) {
                Entry entry = toEntry(p);
                if (entry != null) {
                    byCode.putIfAbsent(entry.code(), entry);
                    usable++;
                }
            }
            log.info("OFF-індекс: сторінка {}{} → {} придатних (разом {})",
                    page, filter.isEmpty() ? "" : " [" + filter + "]", usable, byCode.size());
            if (parsed.products().size() < PAGE_SIZE) {
                break;
            }
            Thread.sleep(SEARCH_THROTTLE_MILLIS);
        }
    }

    private Entry toEntry(SearchProduct p) {
        if (p.code() == null || p.nutriments() == null) {
            return null;
        }
        java.util.OptionalDouble grams = QuantityParser.parseGrams(p.quantity());
        if (grams.isEmpty() || grams.getAsDouble() <= 0) {
            return null;
        }
        String name = p.productNamePl() != null && !p.productNamePl().isBlank()
                ? p.productNamePl() : p.productName();
        if (name == null || name.isBlank()) {
            return null;
        }
        var n = p.nutriments();
        Double kcal = n.energyKcal100g();
        if (kcal == null && n.energyKj100g() != null) {
            kcal = n.energyKj100g() / 4.184;
        }
        if (kcal == null || kcal <= 0 || kcal > 950 || n.proteins100g() == null) {
            return null;
        }
        Macros macros = new Macros(kcal, n.proteins100g(),
                n.fat100g() == null ? 0 : n.fat100g(),
                n.carbohydrates100g() == null ? 0 : n.carbohydrates100g());
        return new Entry(p.code(), name.strip(), p.brands(), grams.getAsDouble(),
                p.imageFrontUrl(), macros, tokens(name), tokens(p.brands()));
    }

    static Set<String> tokens(String s) {
        if (s == null) {
            return Set.of();
        }
        String normalized = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('ł', 'l').replace('Ł', 'l')
                .toLowerCase(Locale.ROOT);
        Set<String> result = new HashSet<>();
        for (String token : normalized.split("[^a-z0-9%]+")) {
            if (token.length() >= 2 && !STOP_TOKENS.contains(token) && !token.matches("\\d+")) {
                result.add(token);
            }
        }
        return result;
    }

    private String fetchJson(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", userAgent)
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("OFF search відповів " + response.statusCode());
        }
        return response.body();
    }

    private List<Entry> loadCache() {
        try {
            if (!Files.exists(cacheFile)) {
                return null;
            }
            CachedIndex cached = objectMapper.readValue(Files.readAllBytes(cacheFile), CachedIndex.class);
            if (cached.generatedAt() == null
                    || Instant.parse(cached.generatedAt()).isBefore(Instant.now().minus(cacheTtl))) {
                return null;
            }
            // токени не серіалізуємо — перераховуємо
            List<Entry> restored = new ArrayList<>();
            for (Entry e : cached.entries()) {
                restored.add(new Entry(e.code(), e.name(), e.brands(), e.grams(), e.imageUrl(),
                        e.macros(), tokens(e.name()), tokens(e.brands())));
            }
            return restored;
        } catch (IOException e) {
            log.warn("OFF-індекс: кеш не читається: {}", e.getMessage());
            return null;
        }
    }

    private void saveCache(List<Entry> toSave) {
        try {
            Files.createDirectories(cacheFile.getParent());
            Files.write(cacheFile, objectMapper.writeValueAsBytes(
                    new CachedIndex(Instant.now().toString(), toSave)));
        } catch (IOException e) {
            log.warn("OFF-індекс: кеш не записався: {}", e.getMessage());
        }
    }

    /** Для тестів: наповнити індекс без мережі. */
    void loadEntries(List<Entry> testEntries) {
        index(testEntries);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(
            String code,
            String name,
            String brands,
            double grams,
            String imageUrl,
            Macros macros,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) Set<String> nameTokens,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) Set<String> brandTokens) {

        public static Entry of(String code, String name, String brands, double grams,
                               String imageUrl, Macros macros) {
            return new Entry(code, name, brands, grams, imageUrl, macros, tokens(name), tokens(brands));
        }
    }

    record CachedIndex(String generatedAt, List<Entry> entries) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchPage(List<SearchProduct> products) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchProduct(
            String code,
            @JsonProperty("product_name") String productName,
            @JsonProperty("product_name_pl") String productNamePl,
            String brands,
            String quantity,
            @JsonProperty("image_front_url") String imageFrontUrl,
            OpenFoodFactsClient.Nutriments nutriments) {}
}
