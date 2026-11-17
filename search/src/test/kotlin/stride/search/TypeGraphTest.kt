package stride.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import stride.core.vfs.VFile
import java.nio.file.Paths

class TypeGraphTest {
    private val root = Paths.get("/project")

    private fun declaration(
        name: String,
        path: String = "src/$name.kt",
        packageName: String? = "demo",
        kind: DeclarationKind = DeclarationKind.CLASS,
        line: Int = 0,
        container: String? = null,
        isPrivate: Boolean = false,
        supertypes: List<String> = emptyList(),
        isInterface: Boolean = false,
        isOverride: Boolean = false,
    ) = Declaration(
        name,
        kind,
        VFile(root.resolve(path)),
        path,
        line,
        container,
        packageName,
        isPrivate,
        supertypes = supertypes,
        isInterface = isInterface,
        isOverride = isOverride,
    )

    private fun graph(
        declarations: List<Declaration>,
        imports: Map<String, List<SourceImport>> = emptyMap(),
    ) = TypeGraph(declarations) { file -> imports[file.path.toString()].orEmpty() }

    private fun context(
        path: String = "src/A.kt",
        packageName: String? = "demo",
        imports: List<SourceImport> = emptyList(),
        enclosing: List<String> = emptyList(),
    ) = NameContext(VFile(root.resolve(path)), packageName, imports, enclosing)

    @Test
    fun `an explicit import wins over a class in the same package`() {
        val samePackage = declaration("Thing", path = "src/Thing.kt")
        val imported = declaration("Thing", path = "src/lib/Thing.kt", packageName = "lib")
        val typeGraph = graph(listOf(samePackage, imported))

        assertEquals(
            listOf(imported),
            typeGraph.resolveType("Thing", context(imports = listOf(SourceImport("lib.Thing")))),
        )
    }

    @Test
    fun `an import outside the project ends the search`() {
        val local = declaration("Missing", path = "src/Missing.kt")
        val typeGraph = graph(listOf(local))
        assertTrue(typeGraph.resolveType("Missing", context(imports = listOf(SourceImport("vendor.Missing")))).isEmpty())
    }

    @Test
    fun `same package, star import and nested names resolve`() {
        val local = declaration("Helper", path = "src/Helper.kt")
        val starred = declaration("Starred", path = "src/lib/Starred.kt", packageName = "lib")
        val outer = declaration("Outer", path = "src/Outer.kt")
        val inner = declaration("Inner", path = "src/Outer.kt", line = 2, container = "Outer")
        val sibling = declaration("Sibling", path = "src/Outer.kt", line = 4)
        val typeGraph = graph(listOf(local, starred, outer, inner, sibling))

        assertEquals(listOf(local), typeGraph.resolveType("Helper", context()))
        assertEquals(
            listOf(starred),
            typeGraph.resolveType("Starred", context(imports = listOf(SourceImport("lib", isStar = true)))),
        )
        assertEquals(listOf(inner), typeGraph.resolveType("Outer.Inner", context()))
        assertEquals(listOf(inner), typeGraph.resolveType("demo.Outer.Inner", context()))
        assertEquals(listOf(inner), typeGraph.resolveType("Inner", context(path = "src/Outer.kt", enclosing = listOf("Outer"))))
        assertEquals(listOf(sibling), typeGraph.resolveType("Sibling", context(path = "src/Outer.kt", enclosing = listOf("Outer"))))
    }

    @Test
    fun `default import names are not project classes`() {
        val typeGraph = graph(emptyList())
        assertTrue(typeGraph.resolveType("String", context()).isEmpty())
        assertTrue(typeGraph.resolveType("List", context()).isEmpty())
    }

    @Test
    fun `a qualified name declared twice resolves to both`() {
        val first = declaration("Twin", path = "a/Twin.kt")
        val second = declaration("Twin", path = "b/Twin.kt")
        val typeGraph = graph(listOf(first, second))
        assertEquals(listOf(first, second), typeGraph.resolveType("Twin", context()))
    }

    @Test
    fun `supertypes, direct subtypes and all subtypes follow the graph`() {
        val shape = declaration("Shape", path = "src/Shape.kt", isInterface = true)
        val base = declaration("Base", path = "src/Base.kt", supertypes = listOf("Shape"))
        val left = declaration("Left", path = "src/Left.kt", supertypes = listOf("Base"))
        val right = declaration("Right", path = "src/Right.kt", supertypes = listOf("Base"))
        val leaf = declaration("Leaf", path = "src/Leaf.kt", supertypes = listOf("Left", "Right"))
        val typeGraph = graph(listOf(shape, base, left, right, leaf))

        assertEquals(listOf("Shape"), typeGraph.supertypes(base).single().resolved.map { it.name })
        assertEquals(listOf("Shape"), typeGraph.supertypes(base).map { it.written })
        assertEquals(listOf(left, right), typeGraph.directSubtypes(base))
        assertEquals(listOf(base, leaf, left, right), typeGraph.allSubtypes(shape))
        assertEquals(listOf("Base", "Leaf", "Left", "Right"), typeGraph.allSubtypes(shape).map { it.name })
        assertEquals(4, typeGraph.allSubtypes(shape).size, "a diamond is visited once")
    }

    @Test
    fun `a cycle ends the walk`() {
        val first = declaration("First", path = "src/First.kt", supertypes = listOf("Second"))
        val second = declaration("Second", path = "src/Second.kt", supertypes = listOf("First"))
        val typeGraph = graph(listOf(first, second))
        assertEquals(listOf(second), typeGraph.allSubtypes(first))
        assertEquals(listOf(second), typeGraph.directSubtypes(first))
    }

    @Test
    fun `implementations list non-interface subtypes and overriding members`() {
        val iface = declaration("Iface", path = "src/Iface.kt", isInterface = true)
        val abstract = declaration("Abstract", path = "src/Abstract.kt", supertypes = listOf("Iface"))
        val impl = declaration("Impl", path = "src/Impl.kt", supertypes = listOf("Iface"))
        val sub = declaration("Sub", path = "src/Sub.kt", supertypes = listOf("Iface"), isInterface = true)
        val leaf = declaration("Leaf", path = "src/Leaf.kt", supertypes = listOf("Sub"))
        val typeGraph = graph(listOf(iface, abstract, impl, sub, leaf))
        assertEquals(listOf(abstract, impl, leaf), typeGraph.implementations(iface))

        val baseMember = declaration("run", path = "src/Base.kt", kind = DeclarationKind.SYMBOL, container = "Base", line = 2)
        val base = declaration("Base", path = "src/Base.kt", line = 1)
        val childMember = declaration(
            "run", path = "src/Child.kt", kind = DeclarationKind.SYMBOL, container = "Child", line = 2, isOverride = true,
        )
        val child = declaration("Child", path = "src/Child.kt", line = 1, supertypes = listOf("Base"))
        val otherMember = declaration("run", path = "src/Other.kt", kind = DeclarationKind.SYMBOL, container = "Other", line = 2)
        val other = declaration("Other", path = "src/Other.kt", line = 1, supertypes = listOf("Base"))
        val memberGraph = graph(listOf(baseMember, base, childMember, child, otherMember, other))
        assertEquals(listOf(childMember), memberGraph.implementations(baseMember))

        val javaBase = declaration("Base", path = "src/Base.java", line = 1)
        val javaBaseMember = declaration("run", path = "src/Base.java", kind = DeclarationKind.SYMBOL, container = "Base", line = 2)
        val javaChild = declaration("Child", path = "src/Child.java", line = 1, supertypes = listOf("Base"))
        val javaChildMember = declaration("run", path = "src/Child.java", kind = DeclarationKind.SYMBOL, container = "Child", line = 2)
        val javaGraph = graph(listOf(javaBase, javaBaseMember, javaChild, javaChildMember))
        assertEquals(listOf(javaChildMember), javaGraph.implementations(javaBaseMember))

        val topLevel = declaration("top", path = "src/Top.kt", kind = DeclarationKind.SYMBOL)
        assertTrue(graph(listOf(topLevel)).implementations(topLevel).isEmpty())
    }

    @Test
    fun `super members take the first match on each path`() {
        val baseMember = declaration("m", path = "src/Base.kt", kind = DeclarationKind.SYMBOL, container = "Base", line = 2)
        val base = declaration("Base", path = "src/Base.kt", line = 1)
        val middleMember = declaration(
            "m", path = "src/Middle.kt", kind = DeclarationKind.SYMBOL, container = "Middle", line = 2, isOverride = true,
        )
        val middle = declaration("Middle", path = "src/Middle.kt", line = 1, supertypes = listOf("Base"))
        val leafMember = declaration(
            "m", path = "src/Leaf.kt", kind = DeclarationKind.SYMBOL, container = "Leaf", line = 2, isOverride = true,
        )
        val leaf = declaration("Leaf", path = "src/Leaf.kt", line = 1, supertypes = listOf("Middle"))
        val typeGraph = graph(listOf(baseMember, base, middleMember, middle, leafMember, leaf))
        assertEquals(listOf(middleMember), typeGraph.superMembers(leafMember))
        assertEquals(listOf(baseMember), typeGraph.superMembers(middleMember))

        val plainMember = declaration("m", path = "src/Base.kt", kind = DeclarationKind.SYMBOL, container = "Base", line = 3)
        assertTrue(graph(listOf(baseMember, base, plainMember)).superMembers(plainMember).isEmpty())

        val javaBase = declaration("Base", path = "src/Base.java", line = 1)
        val javaBaseMember = declaration("m", path = "src/Base.java", kind = DeclarationKind.SYMBOL, container = "Base", line = 2)
        val javaLeaf = declaration("Leaf", path = "src/Leaf.java", line = 1, supertypes = listOf("Base"))
        val javaLeafMember = declaration("m", path = "src/Leaf.java", kind = DeclarationKind.SYMBOL, container = "Leaf", line = 2)
        val javaGraph = graph(listOf(javaBase, javaBaseMember, javaLeaf, javaLeafMember))
        assertEquals(listOf(javaBaseMember), javaGraph.superMembers(javaLeafMember))
    }

    @Test
    fun `super members stop each diamond branch at its own first match`() {
        val baseMember = declaration("m", path = "src/Base.kt", kind = DeclarationKind.SYMBOL, container = "Base", line = 2)
        val base = declaration("Base", path = "src/Base.kt", line = 1)
        val leftMember = declaration("m", path = "src/Left.kt", kind = DeclarationKind.SYMBOL, container = "Left", line = 2, isOverride = true)
        val left = declaration("Left", path = "src/Left.kt", line = 1, supertypes = listOf("Base"))
        val rightMember = declaration("m", path = "src/Right.kt", kind = DeclarationKind.SYMBOL, container = "Right", line = 2, isOverride = true)
        val right = declaration("Right", path = "src/Right.kt", line = 1, supertypes = listOf("Base"))
        val leafMember = declaration("m", path = "src/Leaf.kt", kind = DeclarationKind.SYMBOL, container = "Leaf", line = 2, isOverride = true)
        val leaf = declaration("Leaf", path = "src/Leaf.kt", line = 1, supertypes = listOf("Left", "Right"))
        val typeGraph = graph(listOf(baseMember, base, leftMember, left, rightMember, right, leafMember, leaf))
        assertEquals(listOf(leftMember, rightMember), typeGraph.superMembers(leafMember))
    }

    @Test
    fun `membersNamed follows supertypes and classOf finds the container`() {
        val baseMember = declaration("m", path = "src/Base.kt", kind = DeclarationKind.SYMBOL, container = "Base", line = 2)
        val otherMember = declaration("n", path = "src/Base.kt", kind = DeclarationKind.SYMBOL, container = "Base", line = 3)
        val base = declaration("Base", path = "src/Base.kt", line = 1)
        val child = declaration("Child", path = "src/Child.kt", line = 1, supertypes = listOf("Base"))
        val typeGraph = graph(listOf(baseMember, otherMember, base, child))
        assertEquals(listOf(baseMember), typeGraph.membersNamed("m", base, includeSupertypes = false))
        assertEquals(listOf(baseMember), typeGraph.membersNamed("m", child, includeSupertypes = true))
        assertTrue(typeGraph.membersNamed("m", child, includeSupertypes = false).isEmpty())
        assertEquals(base, typeGraph.classOf(baseMember))
    }

    @Test
    fun `resolveCallee prefers members then supertypes then top level functions then classes`() {
        val childMember = declaration(
            "run", path = "src/Child.kt", kind = DeclarationKind.SYMBOL, container = "Child", line = 2, isPrivate = true,
        )
        val baseMember = declaration("run", path = "src/Base.kt", kind = DeclarationKind.SYMBOL, container = "Base", line = 2)
        val base = declaration("Base", path = "src/Base.kt", line = 1)
        val child = declaration("Child", path = "src/Child.kt", line = 1, supertypes = listOf("Base"))
        val topLevel = declaration("run", path = "src/Top.kt", kind = DeclarationKind.SYMBOL)
        val runnable = declaration("Runnable", path = "src/Runnable.kt", isInterface = true)
        val typeGraph = graph(listOf(childMember, baseMember, base, child, topLevel, runnable))

        val inside = context(path = "src/Child.kt", enclosing = listOf("Child"))
        assertEquals(listOf(baseMember, childMember), typeGraph.resolveCallee(CallSite("run", 0, 3), inside))

        val outside = context(path = "src/Other.kt", enclosing = listOf())
        assertEquals(listOf(topLevel), typeGraph.resolveCallee(CallSite("run", 0, 3), outside))
        assertEquals(listOf(runnable), typeGraph.resolveCallee(CallSite("Runnable", 0, 8), outside))
    }

    @Test
    fun `a receiver resolved to a class limits the callee to that class family`() {
        val baseMember = declaration("m", path = "src/Base.kt", kind = DeclarationKind.SYMBOL, container = "Base", line = 2)
        val base = declaration("Base", path = "src/Base.kt", line = 1)
        val childMember = declaration("m", path = "src/Child.kt", kind = DeclarationKind.SYMBOL, container = "Child", line = 2)
        val child = declaration("Child", path = "src/Child.kt", line = 1, supertypes = listOf("Base"))
        val unrelatedMember = declaration("m", path = "src/Other.kt", kind = DeclarationKind.SYMBOL, container = "Other", line = 2)
        val unrelated = declaration("Other", path = "src/Other.kt", line = 1)
        val topLevel = declaration("m", path = "src/Top.kt", kind = DeclarationKind.SYMBOL)
        val typeGraph = graph(listOf(baseMember, base, childMember, child, unrelatedMember, unrelated, topLevel))

        val context = context(path = "src/A.kt")
        assertEquals(
            listOf(baseMember, childMember),
            typeGraph.resolveCallee(CallSite("m", 0, 1, receiver = "Child"), context),
        )
        assertEquals(
            listOf(baseMember, childMember, unrelatedMember, topLevel),
            typeGraph.resolveCallee(CallSite("m", 0, 1, receiver = "?"), context),
        )
        val privateMember = declaration(
            "m", path = "src/Private.kt", kind = DeclarationKind.SYMBOL, container = "Private", line = 2, isPrivate = true,
        )
        val privateGraph = graph(listOf(privateMember))
        assertTrue(
            privateGraph.resolveCallee(CallSite("m", 0, 1, receiver = "?"), context).isEmpty(),
            "a private member counts only inside its own file",
        )
    }

    @Test
    fun `this and super receivers stay inside the enclosing class family`() {
        val baseMember = declaration("area", path = "src/Base.kt", kind = DeclarationKind.SYMBOL, container = "Base", line = 2)
        val base = declaration("Base", path = "src/Base.kt", line = 1)
        val childMember = declaration("area", path = "src/Child.kt", kind = DeclarationKind.SYMBOL, container = "Child", line = 2)
        val child = declaration("Child", path = "src/Child.kt", line = 1, supertypes = listOf("Base"))
        val otherMember = declaration("area", path = "src/Other.kt", kind = DeclarationKind.SYMBOL, container = "Other", line = 2)
        val other = declaration("Other", path = "src/Other.kt", line = 1)
        val topLevel = declaration("area", path = "src/Top.kt", kind = DeclarationKind.SYMBOL)
        val typeGraph = graph(listOf(baseMember, base, childMember, child, otherMember, other, topLevel))

        val inside = context(path = "src/Child.kt", enclosing = listOf("Child"))
        assertEquals(
            listOf(baseMember, childMember),
            typeGraph.resolveCallee(CallSite("area", 0, 4, receiver = "this"), inside),
        )
        assertEquals(
            listOf(baseMember),
            typeGraph.resolveCallee(CallSite("area", 0, 4, receiver = "super"), inside),
        )
    }

    @Test
    fun `mayCall applies the visibility and receiver rules`() {
        val privateMember = declaration(
            "secret", path = "src/Base.kt", kind = DeclarationKind.SYMBOL, container = "Base", line = 2, isPrivate = true,
        )
        val baseMember = declaration("m", path = "src/Base.kt", kind = DeclarationKind.SYMBOL, container = "Base", line = 3)
        val base = declaration("Base", path = "src/Base.kt", line = 1)
        val child = declaration("Child", path = "src/Child.kt", line = 1, supertypes = listOf("Base"))
        val unrelated = declaration("Unrelated", path = "src/Other.kt", line = 1)
        val topLevel = declaration("top", path = "src/Top.kt", kind = DeclarationKind.SYMBOL, packageName = "demo")
        val otherPackageTop = declaration("far", path = "src/Far.kt", kind = DeclarationKind.SYMBOL, packageName = "other")
        val typeGraph = graph(listOf(privateMember, baseMember, base, child, unrelated, topLevel, otherPackageTop))

        assertFalse(
            typeGraph.mayCall(privateMember, CallSite("secret", 0, 6), context(path = "src/Other.kt")),
            "a private member is not visible from another file",
        )
        assertTrue(typeGraph.mayCall(privateMember, CallSite("secret", 0, 6), context(path = "src/Base.kt", enclosing = listOf("Base"))))

        assertTrue(typeGraph.mayCall(topLevel, CallSite("top", 0, 3), context()))
        assertFalse(
            typeGraph.mayCall(otherPackageTop, CallSite("far", 0, 3), context()),
            "a top level function in another package needs an import",
        )
        assertTrue(
            typeGraph.mayCall(
                otherPackageTop,
                CallSite("far", 0, 3),
                context(imports = listOf(SourceImport("other.far"))),
            ),
        )
        assertTrue(
            typeGraph.mayCall(
                otherPackageTop,
                CallSite("far", 0, 3),
                context(imports = listOf(SourceImport("other", isStar = true))),
            ),
        )

        assertTrue(typeGraph.mayCall(baseMember, CallSite("m", 0, 1), context(path = "src/Base.kt", enclosing = listOf("Base"))))
        assertTrue(typeGraph.mayCall(baseMember, CallSite("m", 0, 1), context(path = "src/Child.kt", enclosing = listOf("Child"))))
        assertFalse(typeGraph.mayCall(baseMember, CallSite("m", 0, 1), context(path = "src/Other.kt", enclosing = listOf("Unrelated"))))

        assertTrue(
            typeGraph.mayCall(baseMember, CallSite("m", 0, 1, receiver = "Child"), context()),
        )
        assertTrue(
            typeGraph.mayCall(baseMember, CallSite("m", 0, 1, receiver = "anyValue"), context()),
            "an unresolved receiver may call anything",
        )
        assertTrue(
            graph(listOf(baseMember, base, unrelated, child)).mayCall(
                baseMember,
                CallSite("m", 0, 1, receiver = "Unrelated"),
                context(),
            ).not(),
            "a receiver resolved to an unrelated class cannot call the member",
        )
    }
}
