package com.fitcollector.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitcollector.ingest.BiedronkaClient.Tile;
import com.fitcollector.ingest.OpenFoodFactsClient.Macros;
import com.fitcollector.ingest.OpenPricesClient.PriceEntry;
import com.fitcollector.model.Product;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Збирає каталог з двох джерел:
 *
 * 1. Онлайн-магазин Biedronka — сотні продуктів з офіційними цінами та фото;
 *    КБЖВ домаплюється з індексу Open Food Facts по (вага, бренд, назва).
 * 2. Open Prices — краудсорсні ціни з чеків інших мереж (Lidl, Kaufland, …);
 *    КБЖВ по штрихкоду з OFF.
 *
 * Результат кешується в data/catalog.json (TTL 24 год), оновлення — у фоні.
 */
@Service
public class CatalogIngestionService {

    private static final Logger log = LoggerFactory.getLogger(CatalogIngestionService.class);

    private final BiedronkaClient biedronkaClient;
    private final OffMatchIndex offIndex;
    private final OpenPricesClient openPricesClient;
    private final OpenFoodFactsClient offClient;
    private final ObjectMapper objectMapper;
    private final Path catalogFile;
    private final Duration catalogTtl;
    private final Set<String> allowedStores;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "catalog-ingest");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile IngestStatus status = new IngestStatus("idle", "Ще не запускалося", null, 0, 0);
    private volatile Consumer<List<Product>> onCatalogReady = products -> {};

    public CatalogIngestionService(
            BiedronkaClient biedronkaClient,
            OffMatchIndex offIndex,
            OpenPricesClient openPricesClient,
            OpenFoodFactsClient offClient,
            ObjectMapper objectMapper,
            @Value("${fitcollector.data-dir:./data}") String dataDir,
            @Value("${fitcollector.catalog-ttl-hours:24}") int catalogTtlHours,
            @Value("${fitcollector.stores:Lidl,Stokrotka,Żabka,Kaufland,Carrefour,Netto,Aldi,Dino,Auchan,E. Leclerc}") List<String> stores) {
        this.biedronkaClient = biedronkaClient;
        this.offIndex = offIndex;
        this.openPricesClient = openPricesClient;
        this.offClient = offClient;
        this.objectMapper = objectMapper;
        this.catalogFile = Path.of(dataDir, "catalog.json");
        this.catalogTtl = Duration.ofHours(catalogTtlHours);
        this.allowedStores = new HashSet<>(stores);
    }

    public void setOnCatalogReady(Consumer<List<Product>> callback) {
        this.onCatalogReady = callback;
    }

    public IngestStatus status() {
        return status;
    }

    /** Кеш каталогу з диска, якщо він існує (незалежно від віку). */
    public Optional<CatalogSnapshot> loadDiskCache() {
        if (!Files.exists(catalogFile)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(Files.readAllBytes(catalogFile), CatalogSnapshot.class));
        } catch (IOException e) {
            log.warn("Не вдалося прочитати {}: {}", catalogFile, e.getMessage());
            return Optional.empty();
        }
    }

    public boolean diskCacheIsFresh() {
        return loadDiskCache()
                .map(s -> s.generatedAt() != null
                        && Instant.parse(s.generatedAt()).isAfter(Instant.now().minus(catalogTtl)))
                .orElse(false);
    }

    /** Запускає оновлення у фоні; false — якщо вже виконується. */
    public boolean refreshAsync() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        executor.submit(() -> {
            try {
                refresh();
            } finally {
                running.set(false);
            }
        });
        return true;
    }

    /** Щогодини перевіряє свіжість кешу; перший запуск — одразу після старту. */
    @Scheduled(initialDelay = 5_000, fixedDelayString = "${fitcollector.refresh-check-millis:3600000}")
    public void refreshIfStale() {
        if (!diskCacheIsFresh()) {
            refreshAsync();
        }
    }

    void refresh() {
        try {
            // КБЖВ з попереднього каталогу: раз знайдений матч не губиться,
            // коли OFF тимчасово флейкає при наступному оновленні
            Map<String, Product> previousWithMacros = new HashMap<>();
            loadDiskCache().map(CatalogSnapshot::products).orElse(List.of()).stream()
                    .filter(p -> p.kcalPer100g() > 0)
                    .forEach(p -> previousWithMacros.put(p.id(), p));

            List<Product> products = new ArrayList<>(ingestBiedronka());
            Set<String> biedronkaBarcodes = new HashSet<>();
            for (Product p : products) {
                if (p.barcode() != null) {
                    biedronkaBarcodes.add(p.barcode());
                }
            }
            products.addAll(ingestOpenPrices(biedronkaBarcodes));
            carryOverMacros(products, previousWithMacros);

            CatalogSnapshot snapshot = new CatalogSnapshot(Instant.now().toString(), products);
            Files.createDirectories(catalogFile.getParent());
            Files.write(catalogFile, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(snapshot));
            status = new IngestStatus("done",
                    "Каталог оновлено: " + products.size() + " продуктів",
                    snapshot.generatedAt(), products.size(), products.size());
            log.info("Каталог: {} продуктів збережено в {}", products.size(), catalogFile);
            onCatalogReady.accept(products);
        } catch (Exception e) {
            log.error("Оновлення каталогу впало", e);
            status = new IngestStatus("error", "Помилка: " + e.getMessage(), null, 0, 0);
        }
    }

    /** Скрапінг лістингів zakupy.biedronka.pl + матчинг КБЖВ з OFF-індексу. */
    private List<Product> ingestBiedronka() {
        List<Product> result = new ArrayList<>();
        try {
            status = new IngestStatus("running", "Завантажую індекс Open Food Facts…", null, 0, 0);
            offIndex.ensureLoaded();
            status = new IngestStatus("running", "Обходжу категорії zakupy.biedronka.pl…", null, 0, 0);
            List<Tile> tiles = biedronkaClient.fetchAllProducts();

            String today = LocalDate.now().toString();
            int matched = 0;
            for (Tile tile : tiles) {
                OptionalDouble grams = QuantityParser.parseGrams(tile.name());
                if (grams.isEmpty() || grams.getAsDouble() < 20 || grams.getAsDouble() > 5000
                        || tile.priceZl() < 0.30 || tile.priceZl() > 200) {
                    continue;
                }
                Optional<OffMatchIndex.Entry> match =
                        offIndex.match(tile.name(), tile.brand(), grams.getAsDouble());
                Macros macros = match.map(OffMatchIndex.Entry::macros).orElse(null);
                if (match.isPresent()) {
                    matched++;
                }
                result.add(new Product(
                        "b-" + tile.pid(),
                        match.map(OffMatchIndex.Entry::code).orElse(null),
                        tile.name(),
                        tile.brand(),
                        "Biedronka",
                        CatalogMapper.categoryFromBiedronka(tile.category(), tile.category2()),
                        tile.priceZl(),
                        grams.getAsDouble(),
                        macros == null ? 0 : round1(macros.kcalPer100g()),
                        macros == null ? 0 : round1(macros.proteinPer100g()),
                        macros == null ? 0 : round1(macros.fatPer100g()),
                        macros == null ? 0 : round1(macros.carbsPer100g()),
                        tile.imageUrl() != null ? tile.imageUrl()
                                : match.map(OffMatchIndex.Entry::imageUrl).orElse(null),
                        "biedronka",
                        today));
            }
            log.info("Biedronka: {} плиток → {} продуктів, з них {} з КБЖВ",
                    tiles.size(), result.size(), matched);
        } catch (Exception e) {
            log.error("Скрапінг Biedronka впав — каталог буде без нього", e);
        }
        return result;
    }

    /** Краудсорсні ціни Open Prices для інших мереж. */
    private List<Product> ingestOpenPrices(Set<String> alreadyCovered) {
        List<Product> result = new ArrayList<>();
        try {
            status = new IngestStatus("running", "Завантажую ціни з Open Prices…", null, 0, 0);
            List<PriceEntry> entries = openPricesClient.fetchAllPlnPrices();

            // Найсвіжіша ціна для кожної пари (штрихкод, магазин)
            Map<String, PriceEntry> latest = new HashMap<>();
            for (PriceEntry e : entries) {
                String store = storeOf(e);
                if (store == null || e.product() == null || e.product().code() == null || e.date() == null) {
                    continue;
                }
                if (store.equals("Biedronka") && alreadyCovered.contains(e.product().code())) {
                    continue;
                }
                String key = e.product().code() + "|" + store;
                PriceEntry prev = latest.get(key);
                if (prev == null || e.date().compareTo(prev.date()) > 0) {
                    latest.put(key, e);
                }
            }

            Set<String> codes = new HashSet<>();
            latest.values().forEach(e -> codes.add(e.product().code()));

            Map<String, Macros> macrosByCode = new HashMap<>();
            int done = 0;
            for (String code : codes) {
                done++;
                if (done % 50 == 0 || done == codes.size()) {
                    status = new IngestStatus("running",
                            "КБЖВ з Open Food Facts: " + done + " з " + codes.size(),
                            null, done, codes.size());
                }
                offClient.fetchMacros(code).ifPresent(m -> macrosByCode.put(code, m));
            }

            for (Map.Entry<String, PriceEntry> e : latest.entrySet()) {
                PriceEntry entry = e.getValue();
                Macros macros = macrosByCode.get(entry.product().code());
                if (macros == null) {
                    continue;
                }
                CatalogMapper.toProduct(entry, macros, storeOf(entry)).ifPresent(result::add);
            }
            log.info("Open Prices: {} продуктів з реальними цінами", result.size());
        } catch (Exception e) {
            log.error("Open Prices впав — каталог буде без нього", e);
        }
        return result;
    }

    /** Продукти, що втратили КБЖВ через флейк OFF, забирають її з попереднього каталогу. */
    private static void carryOverMacros(List<Product> products, Map<String, Product> previousWithMacros) {
        if (previousWithMacros.isEmpty()) {
            return;
        }
        int carried = 0;
        for (int i = 0; i < products.size(); i++) {
            Product p = products.get(i);
            Product prev = previousWithMacros.get(p.id());
            if (p.kcalPer100g() > 0 || prev == null) {
                continue;
            }
            products.set(i, new Product(
                    p.id(),
                    p.barcode() != null ? p.barcode() : prev.barcode(),
                    p.name(), p.brand(), p.store(), p.category(),
                    p.packagePriceZl(), p.packageGrams(),
                    prev.kcalPer100g(), prev.proteinPer100g(), prev.fatPer100g(), prev.carbsPer100g(),
                    p.imageUrl() != null ? p.imageUrl() : prev.imageUrl(),
                    p.priceSource(), p.priceDate()));
            carried++;
        }
        if (carried > 0) {
            log.info("КБЖВ перенесено з попереднього каталогу для {} продуктів", carried);
        }
    }

    private String storeOf(PriceEntry entry) {
        if (entry.location() == null) {
            return null;
        }
        String brand = entry.location().osmBrand() != null
                ? entry.location().osmBrand()
                : entry.location().osmName();
        if (brand == null) {
            return null;
        }
        return brand.equals("Biedronka") || allowedStores.contains(brand) ? brand : null;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public record IngestStatus(String state, String message, String lastRefresh, int done, int total) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CatalogSnapshot(String generatedAt, List<Product> products) {}
}
