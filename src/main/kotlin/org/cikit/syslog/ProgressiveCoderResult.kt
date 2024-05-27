package org.cikit.syslog

sealed class ProgressiveCoderResult {

    data object Underflow : ProgressiveCoderResult()

    data object Overflow : ProgressiveCoderResult()

    class Malformed(val length: Int) : ProgressiveCoderResult()

    class Unmappable(val length: Int) : ProgressiveCoderResult()

    data object EOF : ProgressiveCoderResult()

}
