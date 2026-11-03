package stride.lang.kotlin

import stride.lang.Commenter

object KotlinCommenter : Commenter {
    override val lineCommentPrefix: String = "//"
    override val blockCommentPrefix: String = "/*"
    override val blockCommentSuffix: String = "*/"
}
