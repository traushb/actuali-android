package io.github.traushb.actuali.support

/** Bundled upstream test vectors, preserved unchanged from the iOS suite. */
object Fixtures {
    fun text(name: String): String = requireNotNull(Fixtures::class.java.getResource("/fixtures/$name")).readText()
}
