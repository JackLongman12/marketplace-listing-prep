package com.listingprep.ml

object Price {
    /** Whole numbers from 20 up, two decimals below (e.g. 330, 12.50). */
    fun round(v: Double): String = if (v >= 20) Math.round(v).toString() else "%.2f".format(v)
}
