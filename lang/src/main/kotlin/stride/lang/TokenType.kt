package stride.lang

class TokenType(val debugName: String) {
    override fun toString(): String = debugName

    companion object {
        val BAD_CHARACTER = TokenType("BAD_CHARACTER")
    }
}
