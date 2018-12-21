package org.cikit.syslog

sealed class ProgressiveCoderResult {

    object Underflow : ProgressiveCoderResult()

    object Overflow : ProgressiveCoderResult()

    class Malformed(val length: Int) : ProgressiveCoderResult()

    class Unmappable(val length: Int) : ProgressiveCoderResult()

    object EOF : ProgressiveCoderResult()

}
