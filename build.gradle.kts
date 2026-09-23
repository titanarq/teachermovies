// Stub root build (#36): no-op `test` and `assembleDebug` so CI stays green until #37 replaces it.
tasks.register("test") {
    group = "verification"
    description = "No-op until the multi-module skeleton (#37) exists."
}
tasks.register("assembleDebug") {
    group = "build"
    description = "No-op until the multi-module skeleton (#37) exists."
}
