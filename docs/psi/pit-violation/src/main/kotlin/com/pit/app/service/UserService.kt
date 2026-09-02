package com.pit.app.service

import com.pit.app.web.UserController // service -> controller (arch forbidden)
import com.pit.app.data.UserDao

class UserService(
    private val dao: UserDao,
) {
    // metrics.maxMethodsByRole (limit=1)
    fun compute(x: String): String = dao.query(x)

    fun helper(controller: UserController): String = controller.handleA()
}
