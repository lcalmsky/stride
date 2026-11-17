package stride.app.hierarchy

import java.nio.file.Path

/** Where a hierarchy row navigates: a file and an offset in its text. */
data class HierarchyLocation(val path: Path, val offset: Int)

/**
 * One node of a hierarchy tree. [children] runs on a background dispatcher the first time the node
 * is expanded and its result is kept, so collapsing and expanding again does not call it twice;
 * `null` makes the node a leaf, and a result with no children makes it a leaf too. A node with
 * [expanded] opens as soon as its parent is shown, and its children that also set [expanded] open
 * in turn. While [children] runs the row list shows `Searching…` under the node; a throw shows
 * `Could not load: <message>`. [location] is where the row opens; a node without one flips its
 * expansion on Enter. [detail] is the secondary text (a path and line, a call count) and
 * [iconName] a file name whose icon the row shows.
 */
class HierarchyNode(
    val text: String,
    val detail: String? = null,
    val location: HierarchyLocation? = null,
    val iconName: String? = null,
    val accent: Boolean = false,
    val expanded: Boolean = false,
    val children: (suspend () -> List<HierarchyNode>)? = null,
)

/** One hierarchy for the Hierarchy tool window: its title, a note on how it was matched, and its roots. */
class HierarchyTree(val title: String, val note: String?, val roots: List<HierarchyNode>)
