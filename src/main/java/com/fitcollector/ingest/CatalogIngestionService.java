package com.fitcollector.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Оркеструє збирання каталогу: ціни з Open Prices → КБЖВ з OFF по штрихкоду →
 * валідний список продуктів. Результат кешується в data/catalog.json (TTL 24 год),
 * оновлення йде у фоновому потоці, статус доступний через /api/status.
 */
@Service
public class CatalogIngestionService {

    private static final Logger log = LoggerFactory.getLogger(CatalogIngestionService.class);

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
            OpenPricesClient openPricesClient,
            OpenFoodFactsClient offClient,
            ObjectMapper objectMapper,
            @Value("${fitcollector.data-dir:./data}") String dataDir,
            @Value("${fitcollector.catalog-ttl-hours:24}") int catalogTtlHours,
            @Value("${fitcollector.stores:Biedronka,Lidl,Stokrotka,Żabka,Kaufland,Carrefour,Netto,Aldi,Dino,Auchan,E. Leclerc}") List<String> stores) {
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

    /** Щодоби перевіряє свіжість кешу; перший запуск — одразу після старту. */
    @Scheduled(initialDelay = 5_000, fixedDelayString = "${fitcollector.refresh-check-millis:3600000}")
    public void refreshIfStale() {
        if (!diskCacheIsFresh()) {
            refreshAsync();
        }
    }

    void refresh() {
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
                String key = e.product().code() + "|" + store;
                PriceEntry prev = latest.get(key);
                if (prev == null || e.date().compareTo(prev.date()) > 0) {
                    latest.put(key, e);
                }
            }

            Set<String> codes = new HashSet<>();
            latest.values().forEach(e -> codes.add(e.product().code()));
            log.info("Open Prices: {} цін → {} пар (продукт, магазин), {} унікальних штрихкодів",
                    entries.size(), latest.size(), codes.size());

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

            List<Product> products = new ArrayList<>();
            for (Map.Entry<String, PriceEntry> e : latest.entrySet()) {
                PriceEntry entry = e.getValue();
                Macros macros = macrosByCode.get(entry.product().code());
                if (macros == null) {
                    continue;
                }
                CatalogMapper.toProduct(entry, macros, storeOf(entry)).ifPresent(products::add);
            }

            CatalogSnapshot snapshot = new CatalogSnapshot(Instant.now().toString(), products);
            Files.createDirectories(catalogFile.getParent());
            Files.write(catalogFile, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(snapshot));
            status = new IngestStatus("done",
                    "Каталог оновлено: " + products.size() + " продуктів",
                    snapshot.generatedAt(), codes.size(), codes.size());
            log.info("Каталог: {} продуктів збережено в {}", products.size(), catalogFile);
            onCatalogReady.accept(products);
        } catch (Exception e) {
            log.error("Оновлення каталогу впало", e);
            status = new IngestStatus("error", "Помилка: " + e.getMessage(), null, 0, 0);
        }
    }

    private String storeOf(PriceEntry entry) {
        if (entry.location() == null) {
            return null;
        }
        String brand = entry.location().osmBrand() != null
                ? entry.location().osmBrand()
                : entry.location().osmName();
        return brand != null && allowedStores.contains(brand) ? brand : null;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public record IngestStatus(String state, String message, String lastRefresh, int done, int total) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CatalogSnapshot(String generatedAt, List<Product> products) {}
}
