package com.fitcollector.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitcollector.model.Product;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Завантажує базу продуктів з products.json на старті застосунку.
 */
@Repository
public class ProductRepository {

    private final List<Product> products;

    public ProductRepository(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource("products.json").getInputStream()) {
            this.products = List.copyOf(objectMapper.readValue(in, new TypeReference<List<Product>>() {}));
        } catch (IOException e) {
            throw new UncheckedIOException("Не вдалося завантажити products.json", e);
        }
    }

    public List<Product> findAll() {
        return products;
    }

    public List<Product> findByStores(Set<String> stores) {
        if (stores == null || stores.isEmpty()) {
            return products;
        }
        return products.stream()
                .filter(p -> stores.contains(p.store()))
                .toList();
    }

    public Set<String> stores() {
        Set<String> result = new TreeSet<>();
        for (Product p : products) {
            result.add(p.store());
        }
        return result;
    }
}
