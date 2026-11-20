// Reflection test runner for the JUnit shim (see JUnitShim.kt). Exit code 1 on any failure.
import org.junit.None
import org.junit.Test
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    var passed = 0
    var failed = 0
    for (className in args) {
        val cls = Class.forName(className)
        for (m in cls.declaredMethods.sortedBy { it.name }) {
            val ann = m.getAnnotation(Test::class.java) ?: continue
            val expected = ann.expected.java
            val instance = cls.getDeclaredConstructor().newInstance()
            try {
                m.invoke(instance)
                if (expected != None::class.java) {
                    failed++; println("FAIL ${cls.simpleName}.${m.name}: expected ${expected.simpleName}")
                } else { passed++; println("ok   ${cls.simpleName}.${m.name}") }
            } catch (e: java.lang.reflect.InvocationTargetException) {
                val cause = e.targetException
                if (expected.isInstance(cause)) { passed++; println("ok   ${cls.simpleName}.${m.name}") }
                else { failed++; println("FAIL ${cls.simpleName}.${m.name}: $cause") }
            }
        }
    }
    println("\n$passed passed, $failed failed")
    exitProcess(if (failed == 0) 0 else 1)
}
