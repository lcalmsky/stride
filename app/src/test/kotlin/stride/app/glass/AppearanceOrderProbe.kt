package stride.app.glass

import kotlin.system.exitProcess

/**
 * Runs in a fresh JVM: query the appearance before AWT, then install AWT and report the
 * application class. The query must not create a plain NSApplication before AWT installs its
 * own subclass; the `beforeClass` line proves nothing was created.
 */
fun main() {
    val beforeClass = NativeAppearance.appClassName()
    val before = NativeAppearance.current()
    java.awt.Toolkit.getDefaultToolkit()
    java.awt.Toolkit.getDefaultToolkit().systemEventQueue
    val frame = java.awt.Frame()
    frame.addNotify()
    val afterClass = NativeAppearance.appClassName()
    val after = NativeAppearance.current()
    println(
        "appearance-order beforeClass=${beforeClass.ifEmpty { "(nil)" }} afterClass=$afterClass " +
            "before=$before after=$after",
    )
    frame.dispose()
    exitProcess(0)
}
