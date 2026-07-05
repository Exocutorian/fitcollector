package com.fitcollector.service;

import java.util.List;

/** Результат оптимізації раціону. */
public record RationResult(
        boolean feasible,
        String message,
        List<Item> items,
        Totals totals) {

    public record Item(
            String productId,
            String name,
            String store,
            double grams,
            double costZl,
            double kcal,
            double proteinG,
            double fatG,
            double carbsG) {}

    public record Totals(
            double costZl,
            double kcal,
            double proteinG,
            double fatG,
            double carbsG) {}

    public static RationResult infeasible(String message) {
        return new RationResult(false, message, List.of(), new Totals(0, 0, 0, 0, 0));
    }
}
