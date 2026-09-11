package magicau.mtkcontroller.data.privilege

object DisplayRequest {
    const val LEASE_MS = 15000
    fun arguments(fps: Int): IntArray {
        require(fps in 30..240)
        return intArrayOf(-1, 0, fps, 0, 0, 0, 100, LEASE_MS, 0, 0, 0, 0, 0, 0)
    }
}
