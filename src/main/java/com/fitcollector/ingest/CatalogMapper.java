package com.fitcollector.ingest;

import com.fitcollector.ingest.OpenFoodFactsClient.Macros;
import com.fitcollector.ingest.OpenPricesClient.PriceEntry;
import com.fitcollector.model.Product;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Перетворює запис Open Prices + КБЖВ з OFF на продукт каталогу,
 * відсіюючи сміття (без штрихкода, без ваги, неправдоподібні значення).
 */
public final class CatalogMapper {

    // Порядок важливий: перший збіг перемагає (специфічніше — вище).
    private static final Map<String, String> CATEGORY_BY_TAG = new LinkedHashMap<>();
    static {
        CATEGORY_BY_TAG.put("en:cheeses", "сир");
        CATEGORY_BY_TAG.put("en:yogurts", "йогурти");
        CATEGORY_BY_TAG.put("en:dairies", "молочка");
        CATEGORY_BY_TAG.put("en:eggs", "яйця");
        CATEGORY_BY_TAG.put("en:meats", "м'ясо");
        CATEGORY_BY_TAG.put("en:seafood", "риба");
        CATEGORY_BY_TAG.put("en:fishes", "риба");
        CATEGORY_BY_TAG.put("en:legumes", "бобові");
        CATEGORY_BY_TAG.put("en:breads", "хліб");
        CATEGORY_BY_TAG.put("en:breakfast-cereals", "крупи");
        CATEGORY_BY_TAG.put("en:cereals-and-potatoes", "крупи");
        CATEGORY_BY_TAG.put("en:pastas", "крупи");
        CATEGORY_BY_TAG.put("en:fruits", "фрукти");
        CATEGORY_BY_TAG.put("en:vegetables", "овочі");
        CATEGORY_BY_TAG.put("en:fats", "жири");
        CATEGORY_BY_TAG.put("en:snacks", "снеки");
        CATEGORY_BY_TAG.put("en:beverages", "напої");
        CATEGORY_BY_TAG.put("en:desserts", "солодке");
    }

    private CatalogMapper() {}

    /**
     * @return продукт або empty, якщо запис непридатний для каталогу
     */
    public static Optional<Product> toProduct(PriceEntry entry, Macros macros, String store) {
        if (entry.product() == null || macros == null) {
            return Optional.empty();
        }
        var p = entry.product();
        if (isBlank(p.code()) || isBlank(p.productName()) || entry.price() == null) {
            return Optional.empty();
        }

        Double quantity = p.productQuantity();
        String unit = p.productQuantityUnit();
        if (quantity == null || quantity <= 0
                || (unit != null && !unit.equalsIgnoreCase("g") && !unit.equalsIgnoreCase("ml"))) {
            return Optional.empty();
        }

        double packageGrams;
        double packagePrice;
        if ("KILOGRAM".equalsIgnoreCase(entry.pricePer())) {
            // ціна вказана за кілограм — перераховуємо на упаковку
            packageGrams = quantity;
            packagePrice = entry.price() * quantity / 1000.0;
        } else {
            packageGrams = quantity;
            packagePrice = entry.price();
        }

        if (!plausible(packageGrams, 20, 5000)
                || !plausible(packagePrice, 0.30, 200)
                || !plausible(macros.kcalPer100g(), 5, 950)
                || macros.proteinPer100g() < 0 || macros.proteinPer100g() > 95) {
            return Optional.empty();
        }

        String id = "op-" + slug(store) + "-" + p.code();
        return Optional.of(new Product(
                id,
                p.code(),
                p.productName().strip(),
                isBlank(p.brands()) ? null : p.brands().strip(),
                store,
                category(p.categoriesTags()),
                round2(packagePrice),
                packageGrams,
                round1(macros.kcalPer100g()),
                round1(macros.proteinPer100g()),
                round1(macros.fatPer100g()),
                round1(macros.carbsPer100g()),
                p.imageUrl(),
                "open-prices",
                entry.date()));
    }

    /** Категорія з GTM-даних лістингу Biedronka (item_category / item_category2). */
    public static String categoryFromBiedronka(String category, String subcategory) {
        String cat = normalize(category);
        String sub = normalize(subcategory);

        if (sub.contains("jaja")) return "яйця";
        if (sub.contains("twarog")) return "молочка";
        if (sub.contains("jogurt") || sub.contains("kefir") || sub.contains("maslank")
                || sub.contains("skyr")) return "йогурти";
        // "sery", "serki" — так; "konserwy" — ні
        if ((sub.startsWith("ser") || sub.contains(" ser")) && !sub.contains("konserw")) return "сир";
        if (sub.contains("ryby") || sub.contains("owoce morza")) return "риба";
        if (sub.contains("lody")) return "солодке";

        if (cat.contains("nabial")) return "молочка";
        if (cat.contains("mieso") || cat.contains("wedlin")) return "м'ясо";
        if (cat.contains("owoce")) return "фрукти";
        if (cat.contains("warzywa")) return "овочі";
        if (cat.contains("piekarnia") || cat.contains("pieczywo")) return "хліб";
        if (cat.contains("napoje")) return "напої";
        if (cat.contains("spozywcze")) {
            if (sub.contains("sypkie") || sub.contains("makaron") || sub.contains("ryz")
                    || sub.contains("kasz") || sub.contains("platki")) return "крупи";
            if (sub.contains("slodycze")) return "солодке";
            if (sub.contains("przekaski")) return "снеки";
            if (sub.contains("roslinne")) return "бобові";
        }
        if (cat.contains("mrozone")) {
            if (sub.contains("owoce")) return "фрукти";
            if (sub.contains("warzywa")) return "овочі";
        }
        return "інше";
    }

    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('ł', 'l').replace('Ł', 'l')
                .toLowerCase(Locale.ROOT);
    }

    public static String category(List<String> tags) {
        if (tags != null) {
            for (Map.Entry<String, String> e : CATEGORY_BY_TAG.entrySet()) {
                if (tags.contains(e.getKey())) {
                    return e.getValue();
                }
            }
        }
        return "інше";
    }

    static String slug(String s) {
        String normalized = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-");
        return normalized.replaceAll("(^-|-$)", "");
    }

    private static boolean plausible(double v, double min, double max) {
        return v >= min && v <= max;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
