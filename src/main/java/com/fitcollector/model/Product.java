package com.fitcollector.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Продукт з магазину. Ціна — за упаковку, КБЖВ — на 100 г продукту.
 *
 * priceSource: "open-prices" — реальна краудсорсна ціна з чеків (prices.openfoodfacts.org),
 * "seed" — орієнтовна ціна з локальної бази.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Product(
        String id,
        String barcode,
        String name,
        String brand,
        String store,
        String category,
        double packagePriceZl,
        double packageGrams,
        double kcalPer100g,
        double proteinPer100g,
        double fatPer100g,
        double carbsPer100g,
        String imageUrl,
        String priceSource,
        String priceDate) {

    public Product {
        if (priceSource == null || priceSource.isBlank()) {
            priceSource = "seed";
        }
    }

    /** Ціна за 100 г продукту, zł. */
    @JsonProperty("pricePer100g")
    public double pricePer100g() {
        return packagePriceZl / packageGrams * 100.0;
    }

    /** Скільки коштує 100 г чистого білка з цього продукту, zł. Null, якщо білка немає. */
    @JsonProperty("pricePer100gProtein")
    public Double pricePer100gProtein() {
        if (proteinPer100g <= 0) {
            return null;
        }
        double proteinInPackage = proteinPer100g * packageGrams / 100.0;
        return packagePriceZl / proteinInPackage * 100.0;
    }

    /** Скільки коштує 1000 ккал з цього продукту, zł. Null, якщо калорій немає. */
    @JsonProperty("pricePer1000Kcal")
    public Double pricePer1000Kcal() {
        if (kcalPer100g <= 0) {
            return null;
        }
        double kcalInPackage = kcalPer100g * packageGrams / 100.0;
        return packagePriceZl / kcalInPackage * 1000.0;
    }

    /** Грамів білка за 1 zł. */
    @JsonProperty("proteinPerZloty")
    public double proteinPerZloty() {
        double proteinInPackage = proteinPer100g * packageGrams / 100.0;
        return proteinInPackage / packagePriceZl;
    }

    /** Ккал за 1 zł. */
    @JsonProperty("kcalPerZloty")
    public double kcalPerZloty() {
        double kcalInPackage = kcalPer100g * packageGrams / 100.0;
        return kcalInPackage / packagePriceZl;
    }
}
