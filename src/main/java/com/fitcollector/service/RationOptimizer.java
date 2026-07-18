package com.fitcollector.service;

import com.fitcollector.model.Product;
import org.apache.commons.math3.optim.MaxIter;
import org.apache.commons.math3.optim.PointValuePair;
import org.apache.commons.math3.optim.linear.LinearConstraint;
import org.apache.commons.math3.optim.linear.LinearConstraintSet;
import org.apache.commons.math3.optim.linear.LinearObjectiveFunction;
import org.apache.commons.math3.optim.linear.NoFeasibleSolutionException;
import org.apache.commons.math3.optim.linear.NonNegativeConstraint;
import org.apache.commons.math3.optim.linear.Relationship;
import org.apache.commons.math3.optim.linear.SimplexSolver;
import org.apache.commons.math3.optim.linear.UnboundedSolutionException;
import org.apache.commons.math3.optim.nonlinear.scalar.GoalType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Складає найдешевший денний раціон під задані КБЖВ.
 *
 * Класична «задача про дієту» (задача Стіглера) як лінійна програма:
 * змінні x_i — кількість i-го продукту в одиницях по 100 г;
 * мінімізуємо сумарну вартість за умов:
 *   ккал ≥ ціль та ккал ≤ ціль·(1+допуск),
 *   білок ≥ мінімум,
 *   жири/вуглеводи ≤ максимум (якщо задані),
 *   0 ≤ x_i ≤ ліміт на продукт.
 */
@Service
public class RationOptimizer {

    /** Порції, менші за це, викидаємо з відповіді як непрактичні. */
    private static final double MIN_PORTION_GRAMS = 15.0;
    /** Округлення порцій для зручності зважування. */
    private static final double PORTION_STEP_GRAMS = 5.0;

    /**
     * Сировина, яку не їдять як є: без цього фільтра розв'язок задачі Стіглера
     * чесно радить харчуватися сирим борошном.
     */
    private final List<String> excludeTerms;

    public RationOptimizer(
            @Value("${fitcollector.ration.exclude-terms:mąka,maka ,cukier,sól,drożdże,ocet,przyprawa,bulion}")
            List<String> excludeTerms) {
        this.excludeTerms = excludeTerms.stream()
                .map(t -> t.toLowerCase(Locale.ROOT))
                .toList();
    }

    public RationResult optimize(List<Product> candidates, RationRequest request) {
        if (request.targetKcal() <= 0) {
            return RationResult.infeasible("Цільова калорійність має бути більшою за нуль.");
        }
        List<Product> products = candidates.stream()
                .filter(p -> p.kcalPer100g() > 0)
                .filter(p -> {
                    String name = p.name().toLowerCase(Locale.ROOT);
                    return excludeTerms.stream().noneMatch(name::contains);
                })
                .toList();
        if (products.isEmpty()) {
            return RationResult.infeasible("Немає продуктів для вибраних магазинів.");
        }

        int n = products.size();
        double[] cost = new double[n];
        double[] kcal = new double[n];
        double[] protein = new double[n];
        double[] fat = new double[n];
        double[] carbs = new double[n];
        for (int i = 0; i < n; i++) {
            Product p = products.get(i);
            cost[i] = p.pricePer100g();
            kcal[i] = p.kcalPer100g();
            protein[i] = p.proteinPer100g();
            fat[i] = p.fatPer100g();
            carbs[i] = p.carbsPer100g();
        }

        List<LinearConstraint> constraints = new ArrayList<>();
        constraints.add(new LinearConstraint(kcal, Relationship.GEQ, request.targetKcal()));
        constraints.add(new LinearConstraint(kcal, Relationship.LEQ,
                request.targetKcal() * (1.0 + RationRequest.KCAL_TOLERANCE)));
        if (request.minProteinG() > 0) {
            constraints.add(new LinearConstraint(protein, Relationship.GEQ, request.minProteinG()));
        }
        if (request.maxFatG() != null) {
            constraints.add(new LinearConstraint(fat, Relationship.LEQ, request.maxFatG()));
        }
        if (request.maxCarbsG() != null) {
            constraints.add(new LinearConstraint(carbs, Relationship.LEQ, request.maxCarbsG()));
        }
        double maxUnitsPerProduct = request.effectiveMaxGramsPerProduct() / 100.0;
        for (int i = 0; i < n; i++) {
            double[] unit = new double[n];
            unit[i] = 1.0;
            constraints.add(new LinearConstraint(unit, Relationship.LEQ, maxUnitsPerProduct));
        }

        double[] solution;
        try {
            PointValuePair result = new SimplexSolver().optimize(
                    new MaxIter(10_000),
                    new LinearObjectiveFunction(cost, 0),
                    new LinearConstraintSet(constraints),
                    GoalType.MINIMIZE,
                    new NonNegativeConstraint(true));
            solution = result.getPoint();
        } catch (NoFeasibleSolutionException e) {
            return RationResult.infeasible(
                    "Неможливо скласти раціон під ці умови. Спробуйте послабити обмеження "
                            + "(менше білка, більше жирів/вуглеводів, більший ліміт на продукт або більше магазинів).");
        } catch (UnboundedSolutionException e) {
            return RationResult.infeasible("Задача не має обмеженого розв'язку — перевірте вхідні дані.");
        }

        List<RationResult.Item> items = new ArrayList<>();
        double totalCost = 0;
        double totalKcal = 0;
        double totalProtein = 0;
        double totalFat = 0;
        double totalCarbs = 0;
        for (int i = 0; i < n; i++) {
            double grams = Math.round(solution[i] * 100.0 / PORTION_STEP_GRAMS) * PORTION_STEP_GRAMS;
            if (grams < MIN_PORTION_GRAMS) {
                continue;
            }
            Product p = products.get(i);
            double factor = grams / 100.0;
            RationResult.Item item = new RationResult.Item(
                    p.id(), p.name(), p.store(), p.category(), p.imageUrl(), grams,
                    round2(cost[i] * factor),
                    Math.round(kcal[i] * factor),
                    round1(protein[i] * factor),
                    round1(fat[i] * factor),
                    round1(carbs[i] * factor));
            items.add(item);
            totalCost += cost[i] * factor;
            totalKcal += kcal[i] * factor;
            totalProtein += protein[i] * factor;
            totalFat += fat[i] * factor;
            totalCarbs += carbs[i] * factor;
        }
        items.sort((a, b) -> Double.compare(b.costZl(), a.costZl()));

        RationResult.Totals totals = new RationResult.Totals(
                round2(totalCost),
                Math.round(totalKcal),
                round1(totalProtein),
                round1(totalFat),
                round1(totalCarbs));
        return new RationResult(true, "OK", items, totals);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
