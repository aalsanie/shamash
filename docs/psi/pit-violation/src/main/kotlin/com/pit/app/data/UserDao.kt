package com.pit.app.data

// Role: repository (endsWith Dao) but package is .data (violates rolePlacement)
class UserDao {
    private val _ignoredByRegex: String = "startsWithUnderscore" // deadcode should ignore due to ^_

    fun query(x: String): String = "q:$x"
}
