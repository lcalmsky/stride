package stride.lang

interface Lexer {
    fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int)

    fun getState(): Int

    fun getTokenType(): TokenType?

    fun getTokenStart(): Int

    fun getTokenEnd(): Int

    fun advance()
}
