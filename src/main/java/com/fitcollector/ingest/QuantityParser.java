package com.fitcollector.ingest;

import java.util.Locale;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Витягає вагу упаковки в грамах з назви продукту:
 * "Kefir 420 g", "Mleko 1,5 l", "Kasza 4x100 g", "Jaja 10 szt".
 * Мілілітри рахуємо як грами (для їжі похибка невелика).
 */
public final class QuantityParser {

    private static final Pattern MULTIPACK =
            Pattern.compile("(\\d+)\\s*[x×]\\s*(\\d+(?:[.,]\\d+)?)\\s*(kg|g|l|ml)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SIMPLE =
            Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(kg|g|l|ml)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PIECES =
            Pattern.compile("(\\d+)\\s*szt", Pattern.CASE_INSENSITIVE);

    private static final double EGG_GRAMS = 60.0;

    private QuantityParser() {}

    public static OptionalDouble parseGrams(String name) {
        if (name == null) {
            return OptionalDouble.empty();
        }
        Matcher mp = MULTIPACK.matcher(name);
        if (mp.find()) {
            double count = Double.parseDouble(mp.group(1));
            double each = toGrams(Double.parseDouble(mp.group(2).replace(',', '.')), mp.group(3));
            return OptionalDouble.of(count * each);
        }
        Matcher s = SIMPLE.matcher(name);
        if (s.find()) {
            return OptionalDouble.of(toGrams(Double.parseDouble(s.group(1).replace(',', '.')), s.group(2)));
        }
        Matcher p = PIECES.matcher(name);
        if (p.find() && name.toLowerCase(Locale.ROOT).contains("jaj")) {
            return OptionalDouble.of(Double.parseDouble(p.group(1)) * EGG_GRAMS);
        }
        return OptionalDouble.empty();
    }

    private static double toGrams(double value, String unit) {
        return switch (unit.toLowerCase(Locale.ROOT)) {
            case "kg", "l" -> value * 1000.0;
            default -> value;
        };
    }
}
