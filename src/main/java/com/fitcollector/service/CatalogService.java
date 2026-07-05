package com.fitcollector.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitcollector.ingest.CatalogIngestionService;
import com.fitcollector.ingest.CatalogIngestionService.CatalogSnapshot;
import com.fitcollector.model.Product;
import com.fitcollector.repository.ProductRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Актуальний каталог = продукти з реальними цінами (Open Prices + OFF) + локальна
 * seed-база як доповнення. Джерело інжесту, за пріоритетом:
 * дисковий кеш → вбудований снапшот (щоб перший запуск працював одразу і офлайн).
 */
@Service
public class CatalogService {

    private static final Logger log = LoggerFactory.getLogger(CatalogService.class);

    private final ProductRepository seedRepository;
    private final CatalogIngestionService ingestionService;
    private final ObjectMapper objectMapper;

    private volatile List<Product> products = List.of();

    public CatalogService(ProductRepository seedRepository,
                          CatalogIngestionService ingestionService,
                          ObjectMapper objectMapper) {
        this.seedRepository = seedRepository;
        this.ingestionService = ingestionService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() {
        List<Product> ingested = ingestionService.loadDiskCache()
                .map(CatalogSnapshot::products)
                .orElseGet(this::loadBundledSnapshot);
        rebuild(ingested);
        ingestionService.setOnCatalogReady(this::rebuild);
    }

    private List<Product> loadBundledSnapshot() {
        ClassPathResource resource = new ClassPathResource("catalog-snapshot.json");
        if (!resource.exists()) {
            return List.of();
        }
        try (InputStream in = resource.getInputStream()) {
            List<Product> fromSnapshot = objectMapper.readValue(in, CatalogSnapshot.class).products();
            log.info("Вбудований снапшот каталогу: {} продуктів", fromSnapshot.size());
            return fromSnapshot;
        } catch (IOException e) {
            log.warn("Не вдалося прочитати вбудований снапшот: {}", e.getMessage());
            return List.of();
        }
    }

    private synchronized void rebuild(List<Product> ingested) {
        List<Product> merged = new ArrayList<>(ingested);
        merged.addAll(seedRepository.findAll());
        this.products = List.copyOf(merged);
        log.info("Каталог зібрано: {} продуктів ({} з реальними цінами, {} seed)",
                merged.size(), ingested.size(), seedRepository.findAll().size());
    }

    public List<Product> findAll() {
        return products;
    }

    public List<Product> findByStores(Set<String> stores) {
        if (stores == null || stores.isEmpty()) {
            return products;
        }
        return products.stream().filter(p -> stores.contains(p.store())).toList();
    }

    public Set<String> stores() {
        Set<String> result = new TreeSet<>();
        for (Product p : products) {
            result.add(p.store());
        }
        return result;
    }

    public Set<String> categories() {
        Set<String> result = new TreeSet<>();
        for (Product p : products) {
            if (p.category() != null) {
                result.add(p.category());
            }
        }
        return result;
    }
}
