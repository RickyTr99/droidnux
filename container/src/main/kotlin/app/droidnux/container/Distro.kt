package app.droidnux.container

/**
 * A downloadable root filesystem.
 *
 * Phase 0 hardcodes one entry; it will move to the :components manifest.
 */
data class Distro(
    val id: String,
    val displayName: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
) {
    companion object {
        /**
         * Official Debian 13 "trixie" slim image for arm64, as published by
         * debuerreotype (the source of the Docker library images). The file
         * name in the OCI layout is its SHA-256, so the checksum is the
         * upstream digest.
         */
        val DEBIAN_TRIXIE = Distro(
            id = "debian-trixie",
            displayName = "Debian 13 (trixie)",
            url = "https://github.com/debuerreotype/docker-debian-artifacts/raw/" +
                "cf1f4a45447842b45e9952e0f018ae734a7341c7/trixie/slim/oci/blobs/rootfs.tar.gz",
            sha256 = "bbeda6b4abb749f743f4547ef5a28070a14de8caef211daa5afb78d5b5fa21ba",
            sizeBytes = 30_200_282,
        )
    }
}
