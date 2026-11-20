// Minimal stand-in for the parts of JUnit 4 used by the SmartFreeze unit tests.
// Used ONLY by scripts/verify_kotlin_core.sh to compile and run the pure-Kotlin tests with kotlinc
// when Gradle/Android SDK are unavailable. In Android Studio the real JUnit 4 is used.
package org.junit

import kotlin.reflect.KClass

class None : Throwable()

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Test(val expected: KClass<out Throwable> = None::class)

object Assert {
    @JvmStatic fun fail(msg: String?): Nothing = throw AssertionError(msg)
    @JvmStatic fun assertTrue(c: Boolean) = assertTrue(null, c)
    @JvmStatic fun assertTrue(msg: String?, c: Boolean) { if (!c) fail(msg ?: "expected true") }
    @JvmStatic fun assertFalse(c: Boolean) { if (c) fail("expected false") }
    @JvmStatic fun assertNull(v: Any?) { if (v != null) fail("expected null but was $v") }
    @JvmStatic fun assertEquals(e: Any?, a: Any?) = assertEquals(null, e, a)
    @JvmStatic fun assertEquals(msg: String?, e: Any?, a: Any?) { if (e != a) fail("${msg ?: ""} expected <$e> but was <$a>") }
    @JvmStatic fun assertEquals(e: Double, a: Double, tol: Double) = assertEquals(null, e, a, tol)
    @JvmStatic fun assertEquals(msg: String?, e: Double, a: Double, tol: Double) {
        if (!(Math.abs(e - a) <= tol)) fail("${msg ?: ""} expected <$e> but was <$a> (tol $tol)")
    }
}
