package kotlin_left

typealias RightBatch = kotlin_right.Container<List<kotlin_right.Payload>>

class KotlinOwner(val batch: RightBatch)
