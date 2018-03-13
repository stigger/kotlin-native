import platform.iconv.*
import kotlinx.cinterop.*

fun main(args: Array<String>) {

    val aliasName = "UTF8"
    val canonicalName = iconv_canonicalize(aliasName)?.toKString()
    println("canonical name for \'$aliasName\' is \'$canonicalName\'")
}
