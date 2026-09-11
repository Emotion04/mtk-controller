// AIDL surface of the elevated UserService that Shizuku spawns.
// The implementation runs inside Shizuku's server process (uid 0 with root,
// uid 2000 with ADB), which is what lets us touch /sys and /proc.
//
// Shizuku also sends a teardown transaction with the code
// ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy (16777115). That value is
// above the maximum id AIDL allows, so it is intercepted in onTransact() rather
// than declared here.
package magicau.mtkcontroller;

interface IRuntimeService {
    /** Run a command through /system/bin/sh -c and return stdout (stderr merged). */
    String exec(String command);

    /** uid the service is running as: 0 = root, 2000 = shell. */
    int getUid();

    /**
     * Run a PowerHAL acquire from this UserService process. Keeping both this
     * and release here makes the process that owns a PowerHAL request stable
     * across activity and app-process recreation.
     */
    int powerHalAcquire(in int[] commands, int durationMs);

    /** Queue release of a handle previously acquired by this UserService. */
    boolean powerHalRelease(int handler);
}
