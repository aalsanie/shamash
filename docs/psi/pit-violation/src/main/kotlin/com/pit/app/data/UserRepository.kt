package com.pit.app.data

// Role: repository (endsWith Repository) but package is .data (violates rolePlacement: expects dao|repository)
class UserRepository {
    fun findById(id: String): String = "user:$id"
}
