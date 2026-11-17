package stride.search

/** What a declaration's own text says about its type. */
sealed interface DeclaredType {
    /**
     * An explicit type, as written without type arguments or `?`: `x: Foo`, a parameter `x: Foo`, a
     * function's return type `fun f(): Foo`, or a Java `Foo x` / `Foo f(...)`. `x: a.b.Foo<T>?` is
     * `a.b.Foo`.
     */
    data class Explicit(val written: String) : DeclaredType

    /**
     * No explicit type, but the whole initializer is a call `Foo(...)` or `a.Foo(...)` of a name
     * starting with an upper-case letter. Only the caller can tell a constructor from a function of
     * that name, so it confirms [written] is a class before using it.
     */
    data class ConstructorCall(val written: String) : DeclaredType

    /** Anything else: the type needs inference a compiler would do. [reason] is shown in the status bar. */
    data class Unknown(val reason: String) : DeclaredType
}

/** Reads declared types from Kotlin and Java text without resolving anything. */
object TypeReferences {
    /**
     * The name offset of the nearest declaration of [name] before [offset] that is in scope at
     * [offset] inside the enclosing member ([DeclarationParser.outline]): a local `val`/`var`, a
     * parameter of the enclosing function or of an enclosing lambda (typed or not), a `for` or
     * `catch` variable; in Java a local, parameter, `for` or `catch` variable. A declaration inside a
     * block that has closed before [offset] is out of scope. `null` when there is none, or when
     * [offset] is not inside a member.
     */
    fun localDeclaration(fileName: String, text: String, offset: Int, name: String): Int? = null

    /**
     * The type declared by the declaration whose name starts at [nameStart]: a property, local,
     * parameter, `for` or `catch` variable, or a function (its return type). A function type, a
     * destructuring declaration, an untyped lambda parameter, Java `var` and every initializer
     * other than a [DeclaredType.ConstructorCall] give [DeclaredType.Unknown] with
     * `"Cannot infer the type of <name> without a compiler; declare it explicitly"`.
     */
    fun declaredType(fileName: String, text: String, nameStart: Int): DeclaredType =
        DeclaredType.Unknown("Cannot infer the type without a compiler; declare it explicitly")
}
