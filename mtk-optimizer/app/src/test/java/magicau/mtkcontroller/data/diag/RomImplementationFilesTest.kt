package magicau.mtkcontroller.data.diag

import org.junit.Assert.*
import org.junit.Test

class RomImplementationFilesTest {
    @Test fun selectsNativeServicesAndCompiledFrameworks() {
        for (path in listOf("/vendor/lib64/libpowerhal.so", "/vendor/bin/hw/vendor.vivoperfservice",
            "/system/framework/vivo-services.jar", "/system/framework/oat/arm64/services.vdex",
            "/system_ext/framework/oat/arm64/vivo-framework.odex", "/vendor/etc/init/vendor.power.rc")) {
            assertTrue(path, RomImplementationFiles.relevant(path))
        }
    }
    @Test fun excludesUnrelatedLibrariesAndFrameworkFiles() {
        for (path in listOf("/vendor/lib64/libcamera.so", "/system/framework/framework-res.apk",
            "/vendor/etc/init/bluetooth.rc", "/system/framework/random.jar")) {
            assertFalse(path, RomImplementationFiles.relevant(path))
        }
    }
}
