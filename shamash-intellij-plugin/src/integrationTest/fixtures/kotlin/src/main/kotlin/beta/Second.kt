package kotlin_right

class Container<T>(
    val owner: kotlin_left.KotlinOwner,
    val payload: T,
)

data class Payload(val name: String)

object Registry {
    val defaultPayload = Payload("default")

    fun create(owner: kotlin_left.KotlinOwner): Container<Payload> =
        Container(owner, defaultPayload)
}
