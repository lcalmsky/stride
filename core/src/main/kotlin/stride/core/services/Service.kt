package stride.core.services

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Service(vararg val value: Level = [Level.APP]) {
    enum class Level { APP, PROJECT }
}
