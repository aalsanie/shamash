package com.pit.app.web

import com.pit.app.data.UserRepository // controller -> repository (arch forbidden)
import com.pit.app.service.UserService

// Role: controller (endsWith Controller) but package is .web (violates rolePlacement)
class UserController(
    private val repo: UserRepository,
    private val svc: UserService,
) {
    // deadcode.unusedPrivateMembers (private + unused)
    private val unusedField: String = "unused"
    private fun unusedMethod(): Int = 42

    // metrics.maxMethodsByRole (limit=1, declaredMethods=2+)
    fun handleA(): String = repo.findById("1")
    fun handleB(): String = svc.compute("x")
}
