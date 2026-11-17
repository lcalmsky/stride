package stride.app.glass

import kotlin.system.exitProcess

/**
 * Runs in a fresh JVM in the same order as `main`: the process property first, then the
 * appearance query before AWT, then AWT and the application class. The query must not create a
 * plain NSApplication before AWT installs its own subclass; the `beforeClass` line proves
 * nothing was created.
 */
fun main() {
    SystemAppearance.prepareProcess()
    val beforeClass = NativeAppearance.appClassName()
    val before = NativeAppearance.current()
    java.awt.Toolkit.getDefaultToolkit()
    java.awt.Toolkit.getDefaultToolkit().systemEventQueue
    val frame = java.awt.Frame()
    frame.addNotify()
    val afterClass = NativeAppearance.appClassName()
    val after = NativeAppearance.current()
    val appAppearance = NativeAppearance.appAppearanceName()
    println(
        "appearance-order beforeClass=${beforeClass.ifEmpty { "(nil)" }} afterClass=$afterClass " +
            "before=$before after=$after appAppearance=${appAppearance.ifEmpty { "(empty)" }}",
    )
    frame.dispose()
    exitProcess(0)
}
