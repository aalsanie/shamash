package com.pit.app.service

import com.pit.app.web.UserController // service -> controller (arch forbidden)
import com.pit.app.data.UserDao

// Role: service and correctly placed under .service
class UserService(
    private val dao: UserDao,
) {
    // metrics.maxMethodsByRole (limit=1)
    fun compute(x: String): String = dao.query(x)

    // extra method to exceed limit
    fun helper(controller: UserController): String = controller.handleA()
}
