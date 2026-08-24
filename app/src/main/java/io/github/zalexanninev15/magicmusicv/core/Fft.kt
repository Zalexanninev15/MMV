package io.github.zalexanninev15.magicmusicv.core

import kotlin.math.cos
import kotlin.math.sin

/**
 * Minimal in-place iterative radix-2 FFT.
 *
 * Deliberately hand-written instead of pulling in a DSP library: the audio thread
 * runs one 1024-point transform every 5.3 ms and an external dependency here would
 * buy nothing but allocation churn and an extra failure point in CI.
 */
class Fft(private val n: Int) {

    private val cosTable = FloatArray(n / 2)
    private val sinTable = FloatArray(n / 2)
    private val rev = IntArray(n)

    init {
        require(n > 1 && (n and (n - 1)) == 0) { "FFT size must be a power of two" }
        for (i in 0 until n / 2) {
            cosTable[i] = cos(2.0 * Math.PI * i / n).toFloat()
            sinTable[i] = sin(2.0 * Math.PI * i / n).toFloat()
        }
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) rev[i] = Integer.reverse(i) ushr (32 - bits)
    }

    /** Transforms [re]/[im] in place. Both arrays must be of size [n]. */
    fun transform(re: FloatArray, im: FloatArray) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var j = i
                var k = 0
                while (j < i + half) {
                    val l = j + half
                    val c = cosTable[k]
                    val s = -sinTable[k]
                    val tre = re[l] * c - im[l] * s
                    val tim = re[l] * s + im[l] * c
                    re[l] = re[j] - tre
                    im[l] = im[j] - tim
                    re[j] += tre
                    im[j] += tim
                    j++
                    k += step
                }
                i += size
            }
            size = size shl 1
        }
    }
}
