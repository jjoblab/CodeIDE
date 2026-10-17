package demo
import okio.Buffer
fun main() { println(Buffer().writeUtf8("Hello").readUtf8()) }
