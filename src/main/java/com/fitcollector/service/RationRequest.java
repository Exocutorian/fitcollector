package com.fitcollector.service;

import java.util.List;

/**
 * Параметри складання раціону.
 *
 * @param targetKcal         цільова калорійність за день (обов'язково, > 0)
 * @param minProteinG        мінімум білка за день, г
 * @param maxFatG            максимум жирів за день, г (опційно)
 * @param maxCarbsG          максимум вуглеводів за день, г (опційно)
 * @param stores             магазини, з яких можна брати продукти (порожньо = всі)
 * @param maxGramsPerProduct обмеження на один продукт, г (для різноманітності; 0 = без обмеження)
 */
public record RationRequest(
        double targetKcal,
        double minProteinG,
        Double maxFatG,
        Double maxCarbsG,
        List<String> stores,
        double maxGramsPerProduct) {

    public static final double DEFAULT_MAX_GRAMS_PER_PRODUCT = 500.0;
    /** Допуск перебору калорій над ціллю (щоб раціон не «роздувався» дешевими калоріями). */
    public static final double KCAL_TOLERANCE = 0.05;

    public double effectiveMaxGramsPerProduct() {
        return maxGramsPerProduct > 0 ? maxGramsPerProduct : DEFAULT_MAX_GRAMS_PER_PRODUCT;
    }
}
