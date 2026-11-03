package stride.lang

class Language(val id: String) {
    override fun equals(other: Any?): Boolean = other is Language && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = id
}
