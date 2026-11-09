package com.listingprep.data

import java.util.Currency
import java.util.Locale

/** A country or territory, including special administrative regions (Hong Kong SAR, Macao SAR). */
data class Region(val code: String, val name: String, val currency: String, val symbol: String)

object Regions {
    val all: List<Region> by lazy {
        Locale.getISOCountries().map(::of).sortedBy { it.name.lowercase() }
    }

    fun of(code: String): Region {
        val locale = Locale("", code)
        val currency = runCatching { Currency.getInstance(locale) }.getOrNull()
        return Region(
            code = code,
            name = locale.getDisplayCountry(Locale.ENGLISH).ifBlank { code },
            currency = currency?.currencyCode.orEmpty(),
            symbol = currency?.getSymbol(Locale.getDefault()).orEmpty(),
        )
    }

    /** The region in Settings, or the phone's own region if none was picked. */
    /** "Glasgow, Scotland, Glasgow City, United Kingdom" style, smallest first. */
    fun describe(settings: Settings): String =
        listOf(settings.city.trim(), settings.areaName.split(", ").reversed().joinToString(", "), current(settings).name)
            .filter { it.isNotBlank() }.joinToString(", ")

    fun current(settings: Settings): Region =
        of(settings.region.ifBlank { Locale.getDefault().country.takeIf { it.length == 2 } ?: "US" })
}
