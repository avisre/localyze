package com.localyze.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Deterministic calculator the model can call for arithmetic, powers,
 * factorials, roots, trig, and common unit conversions. Replaces the
 * model's unreliable mental arithmetic with exact computation.
 */
@Singleton
class CalculatorTool @Inject constructor() : Tool {

    override val name = "calculator"
    override val description =
        "Evaluate math expressions and unit conversions deterministically. " +
            "USE THIS for any arithmetic, factorial, square root, percentage, power, " +
            "trigonometry, or unit conversion question. Examples: '7*8', '10!', " +
            "'sqrt(144)', '(15% of 240)', '2^10', 'sin(pi/2)'. For temperature, " +
            "length, mass, or volume conversion, set 'mode' to 'convert' and supply " +
            "'value', 'from', 'to'. Always prefer this tool over computing in your head."

    override fun getParameterSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("mode", buildJsonObject {
                put("type", "string")
                put("description", "'eval' for math expressions (default), 'convert' for unit conversion")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("eval"))
                    add(JsonPrimitive("convert"))
                })
            })
            put("expression", buildJsonObject {
                put("type", "string")
                put("description", "Math expression when mode='eval'. Supports +, -, *, /, %, ^, !, sqrt, sin, cos, tan, log, ln, abs, round, floor, pi, e.")
            })
            put("value", buildJsonObject {
                put("type", "number")
                put("description", "Numeric value to convert when mode='convert'")
            })
            put("from", buildJsonObject {
                put("type", "string")
                put("description", "Source unit (e.g. F, C, K, km, mi, m, ft, kg, lb, g, oz, L, gal)")
            })
            put("to", buildJsonObject {
                put("type", "string")
                put("description", "Target unit")
            })
        })
        put("required", buildJsonArray { })
    }

    override suspend fun execute(args: JsonObject): String {
        val mode = (args["mode"] as? JsonPrimitive)?.content ?: "eval"
        return when (mode) {
            "convert" -> handleConvert(args)
            "eval" -> handleEval(args)
            else -> errorResult("Unknown mode '$mode'. Use 'eval' or 'convert'.")
        }
    }

    private fun handleEval(args: JsonObject): String {
        val expr = (args["expression"] as? JsonPrimitive)?.content
            ?: return errorResult("Missing 'expression'.")
        if (expr.isBlank()) return errorResult("Empty expression.")
        return try {
            val value = Evaluator(expr).parse()
            buildJsonObject {
                put("expression", expr)
                put("value", formatNumber(value))
                put("raw", value)
            }.toString()
        } catch (e: Exception) {
            errorResult("Cannot evaluate '$expr': ${e.message}")
        }
    }

    private fun handleConvert(args: JsonObject): String {
        val rawValue = (args["value"] as? JsonPrimitive)?.content?.toDoubleOrNull()
            ?: return errorResult("Missing or non-numeric 'value'.")
        val from = (args["from"] as? JsonPrimitive)?.content?.trim()
            ?: return errorResult("Missing 'from' unit.")
        val to = (args["to"] as? JsonPrimitive)?.content?.trim()
            ?: return errorResult("Missing 'to' unit.")
        return try {
            val out = convertUnits(rawValue, from, to)
            buildJsonObject {
                put("value", rawValue)
                put("from", from)
                put("to", to)
                put("result", formatNumber(out))
                put("raw", out)
            }.toString()
        } catch (e: Exception) {
            errorResult("Cannot convert $rawValue $from -> $to: ${e.message}")
        }
    }

    private fun formatNumber(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return v.toString()
        val rounded = round(v)
        return if (abs(v - rounded) < 1e-9 && abs(rounded) < 1e15) {
            rounded.toLong().toString()
        } else {
            "%.6g".format(v).trimEnd('0').trimEnd('.')
        }
    }

    private fun errorResult(message: String): String = buildJsonObject {
        put("error", message)
    }.toString()

    // ── Expression evaluator (recursive descent) ───────────────────────────

    private class Evaluator(input: String) {
        private val src = input
            .replace("×", "*")
            .replace("÷", "/")
            .replace("−", "-")
            .replace("²", "^2")
            .replace("³", "^3")
            .replace("π", "pi")
        private var pos = 0

        fun parse(): Double {
            val v = parseExpression()
            skipWs()
            if (pos < src.length) error("Unexpected '${src.substring(pos)}'")
            return v
        }

        // expression := term (("+"|"-") term)*
        private fun parseExpression(): Double {
            var left = parseTerm()
            while (true) {
                skipWs()
                when (peek()) {
                    '+' -> { pos++; left += parseTerm() }
                    '-' -> { pos++; left -= parseTerm() }
                    else -> return left
                }
            }
        }

        // term := factor (("*"|"/"|"%") factor)*
        private fun parseTerm(): Double {
            var left = parseFactor()
            while (true) {
                skipWs()
                when (peek()) {
                    '*' -> { pos++; left *= parseFactor() }
                    '/' -> { pos++; val r = parseFactor(); if (r == 0.0) error("division by zero"); left /= r }
                    '%' -> { pos++; val r = parseFactor(); if (r == 0.0) error("modulo by zero"); left %= r }
                    else -> return left
                }
            }
        }

        // factor := unary ("^" factor)?  (right-assoc)
        private fun parseFactor(): Double {
            val base = parseUnary()
            skipWs()
            if (peek() == '^') {
                pos++
                return base.pow(parseFactor())
            }
            return base
        }

        // unary := ("+"|"-") unary | postfix
        private fun parseUnary(): Double {
            skipWs()
            return when (peek()) {
                '+' -> { pos++; parseUnary() }
                '-' -> { pos++; -parseUnary() }
                else -> parsePostfix()
            }
        }

        // postfix := atom ("!")*
        private fun parsePostfix(): Double {
            var v = parseAtom()
            while (true) {
                skipWs()
                if (peek() == '!') { pos++; v = factorial(v) } else return v
            }
        }

        private fun parseAtom(): Double {
            skipWs()
            val c = peek() ?: error("unexpected end of expression")
            if (c == '(') {
                pos++
                val v = parseExpression()
                skipWs()
                if (peek() != ')') error("expected ')'")
                pos++
                return v
            }
            if (c.isDigit() || c == '.') return parseNumber()
            if (c.isLetter()) return parseIdentifier()
            error("Unexpected character '$c'")
        }

        private fun parseNumber(): Double {
            val start = pos
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.' || src[pos] == '_')) pos++
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                pos++
                if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                while (pos < src.length && src[pos].isDigit()) pos++
            }
            val raw = src.substring(start, pos).replace("_", "")
            return raw.toDoubleOrNull() ?: error("bad number '$raw'")
        }

        private fun parseIdentifier(): Double {
            val start = pos
            while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
            val name = src.substring(start, pos).lowercase()
            skipWs()
            if (peek() == '(') {
                pos++
                val arg = parseExpression()
                skipWs()
                if (peek() != ')') error("expected ')' after $name argument")
                pos++
                return applyFunction(name, arg)
            }
            return when (name) {
                "pi" -> PI
                "e" -> Math.E
                "tau" -> 2 * PI
                else -> error("unknown identifier '$name'")
            }
        }

        private fun applyFunction(name: String, x: Double): Double = when (name) {
            "sqrt" -> sqrt(x)
            "abs" -> abs(x)
            "ln" -> ln(x)
            "log", "log10" -> log10(x)
            "exp" -> exp(x)
            "sin" -> sin(x)
            "cos" -> cos(x)
            "tan" -> tan(x)
            "round" -> round(x)
            "floor" -> floor(x)
            "ceil" -> kotlin.math.ceil(x)
            "fact", "factorial" -> factorial(x)
            else -> error("unknown function '$name'")
        }

        private fun factorial(n: Double): Double {
            if (n < 0 || n != floor(n)) error("factorial requires non-negative integer")
            if (n > 170) error("factorial overflow (max 170)")
            var r = 1.0
            var i = 2
            while (i <= n.toInt()) { r *= i; i++ }
            return r
        }

        private fun skipWs() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun peek(): Char? = if (pos < src.length) src[pos] else null

        private fun error(msg: String): Nothing = throw IllegalArgumentException(msg)
    }

    // ── Unit conversion ────────────────────────────────────────────────────

    private fun convertUnits(value: Double, fromRaw: String, toRaw: String): Double {
        val from = normalizeUnit(fromRaw)
        val to = normalizeUnit(toRaw)
        if (from == to) return value

        // Temperature is offset-based, handle separately
        val tempUnits = setOf("c", "f", "k")
        if (from in tempUnits || to in tempUnits) {
            if (from !in tempUnits || to !in tempUnits) {
                throw IllegalArgumentException("cannot convert between temperature and non-temperature units")
            }
            val celsius = when (from) {
                "c" -> value
                "f" -> (value - 32.0) * 5.0 / 9.0
                "k" -> value - 273.15
                else -> throw IllegalArgumentException("unknown temperature unit '$fromRaw'")
            }
            return when (to) {
                "c" -> celsius
                "f" -> celsius * 9.0 / 5.0 + 32.0
                "k" -> celsius + 273.15
                else -> throw IllegalArgumentException("unknown temperature unit '$toRaw'")
            }
        }

        // Linear units: convert via canonical base
        val (fromFactor, fromCategory) = unitFactor(from)
            ?: throw IllegalArgumentException("unknown unit '$fromRaw'")
        val (toFactor, toCategory) = unitFactor(to)
            ?: throw IllegalArgumentException("unknown unit '$toRaw'")
        if (fromCategory != toCategory) {
            throw IllegalArgumentException("incompatible units: $fromRaw is $fromCategory, $toRaw is $toCategory")
        }
        return value * fromFactor / toFactor
    }

    private fun normalizeUnit(unit: String): String {
        // Try the raw lowercased form first, THEN a trimmed-trailing-period/s
        // form. Doing trimEnd('.', 's') unconditionally before the lookup
        // mangled words that legitimately end in 's' — most painfully,
        // "celsius" → "celsiu", which then failed temperature conversion
        // ("Convert 100 °F to Celsius" returned "cannot convert between
        // temperature and non-temperature units").
        val lower = unit.lowercase().trim()
        aliasFor(lower)?.let { return it }
        val trimmed = lower.trimEnd('.', 's')
        aliasFor(trimmed)?.let { return it }
        return trimmed
    }

    private fun aliasFor(s: String): String? = when (s) {
        "celsius", "centigrade", "°c", "deg c", "c" -> "c"
        "fahrenheit", "°f", "deg f", "f" -> "f"
        "kelvin", "°k", "k" -> "k"
        "kilometer", "kilometre", "kilometers", "kilometres", "km" -> "km"
        "meter", "metre", "meters", "metres", "m" -> "m"
        "centimeter", "centimetre", "centimeters", "centimetres", "cm" -> "cm"
        "millimeter", "millimetre", "millimeters", "millimetres", "mm" -> "mm"
        "mile", "miles", "mi" -> "mi"
        "foot", "feet", "ft" -> "ft"
        "inch", "inches", "in" -> "in"
        "yard", "yards", "yd" -> "yd"
        "kilogram", "kilograms", "kg" -> "kg"
        "gram", "grams", "g" -> "g"
        "pound", "pounds", "lbs", "lb" -> "lb"
        "ounce", "ounces", "oz" -> "oz"
        "liter", "litre", "liters", "litres", "l" -> "l"
        "milliliter", "millilitre", "milliliters", "millilitres", "ml" -> "ml"
        "gallon", "gallons", "gal" -> "gal"
        else -> null
    }

    /** Returns (factor-to-base, category) where base unit per category is meter / kilogram / liter. */
    private fun unitFactor(unit: String): Pair<Double, String>? = when (unit) {
        // Length (base: meter)
        "m" -> 1.0 to "length"
        "km" -> 1000.0 to "length"
        "cm" -> 0.01 to "length"
        "mm" -> 0.001 to "length"
        "mi" -> 1609.344 to "length"
        "ft" -> 0.3048 to "length"
        "in" -> 0.0254 to "length"
        "yd" -> 0.9144 to "length"
        "nmi" -> 1852.0 to "length"
        // Mass (base: kilogram)
        "kg" -> 1.0 to "mass"
        "g" -> 0.001 to "mass"
        "mg" -> 1e-6 to "mass"
        "lb" -> 0.45359237 to "mass"
        "oz" -> 0.028349523125 to "mass"
        "ton", "tonne" -> 1000.0 to "mass"
        // Volume (base: liter)
        "l" -> 1.0 to "volume"
        "ml" -> 0.001 to "volume"
        "gal" -> 3.785411784 to "volume"
        "qt" -> 0.946352946 to "volume"
        "pt" -> 0.473176473 to "volume"
        "cup" -> 0.2365882365 to "volume"
        "floz" -> 0.0295735296875 to "volume"
        // Time (base: second)
        "s", "sec" -> 1.0 to "time"
        "min" -> 60.0 to "time"
        "h", "hr", "hour" -> 3600.0 to "time"
        "day" -> 86400.0 to "time"
        else -> null
    }
}
