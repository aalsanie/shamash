package kotlin_left

typealias RightBatch = kotlin_right.Container<List<kotlin_right.Payload>>

class KotlinOwner(val batch: RightBatch) {
    fun create(): kotlin_right.Container<kotlin_right.Payload> = kotlin_right.Registry.create(this)
}
