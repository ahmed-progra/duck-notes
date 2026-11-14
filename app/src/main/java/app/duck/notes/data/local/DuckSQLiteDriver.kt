package app.duck.notes.data.local

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/** The bundled driver's UTF-16 binding consumes a leading byte-order marker as encoding metadata. */
internal class DuckSQLiteDriver : SQLiteDriver {
    private val delegate = BundledSQLiteDriver()

    override fun open(fileName: String): SQLiteConnection {
        val connection = delegate.open(fileName)
        return object : SQLiteConnection by connection {
            override fun prepare(sql: String): SQLiteStatement {
                val statement = connection.prepare(sql)
                return object : SQLiteStatement by statement {
                    override fun bindText(index: Int, value: String) {
                        // Supply native byte order explicitly so the user's first character remains content.
                        statement.bindText(index, when (value.firstOrNull()) {
                            '\uFEFF', '\uFFFE' -> "\uFEFF$value"
                            else -> value
                        })
                    }
                }
            }
        }
    }
}
