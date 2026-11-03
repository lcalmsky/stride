package stride.lang.json

import stride.lang.Commenter

object JsonCommenter : Commenter {
    override val lineCommentPrefix: String = "//"
    override val blockCommentPrefix: String = "/*"
    override val blockCommentSuffix: String = "*/"
}
