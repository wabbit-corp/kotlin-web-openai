// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.web.openai

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenAILiveTestSupportTest {
    @Test
    fun `live secret path resolves repo root file from nested cwd without walking above repo`() {
        val repoRoot = createTempRepoRoot()
        val nestedCwd = repoRoot.resolve("nested/module").createDirectories()
        val repoSecret = Files.writeString(repoRoot.resolve("keys.env"), "OPENAI_API_KEY=sk-repo")
        Files.writeString(repoRoot.parent.resolve("keys.env"), "OPENAI_API_KEY=sk-parent")

        val resolved = resolveLiveKeysEnvPath(env = emptyMap(), cwd = nestedCwd)

        assertEquals(repoSecret.toAbsolutePath().normalize(), resolved)
    }

    @Test
    fun `explicit live secret path overrides repo root fallback`() {
        val repoRoot = createTempRepoRoot()
        val nestedCwd = repoRoot.resolve("nested/module").createDirectories()
        Files.writeString(repoRoot.resolve("keys.env"), "OPENAI_API_KEY=sk-repo")
        val explicit = Files.writeString(repoRoot.parent.resolve("external-keys.env"), "OPENAI_API_KEY=sk-explicit")

        val resolved =
            resolveLiveKeysEnvPath(
                env = mapOf("WABBIT_LIVE_KEYS_ENV_PATH" to explicit.toAbsolutePath().toString()),
                cwd = nestedCwd,
            )

        assertEquals(explicit.toAbsolutePath().normalize(), resolved)
    }

    @Test
    fun `missing explicit live secret path does not fall back to repo root`() {
        val repoRoot = createTempRepoRoot()
        val nestedCwd = repoRoot.resolve("nested/module").createDirectories()
        Files.writeString(repoRoot.resolve("keys.env"), "OPENAI_API_KEY=sk-repo")

        val resolved =
            resolveLiveKeysEnvPath(
                env = mapOf("WABBIT_LIVE_KEYS_ENV_PATH" to repoRoot.resolve("missing.env").toString()),
                cwd = nestedCwd,
            )

        assertNull(resolved)
    }

    private fun createTempRepoRoot(): Path {
        val repoRoot = Files.createTempDirectory("openai-live-support-test")
        Files.writeString(repoRoot.resolve(".git"), "gitdir: /tmp/fake")
        return repoRoot
    }
}
