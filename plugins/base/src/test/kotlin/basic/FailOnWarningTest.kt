/*
 * Copyright 2014-2023 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package basic

import org.jetbrains.dokka.DokkaException
import org.jetbrains.dokka.base.testApi.testRunner.BaseAbstractTest
import org.jetbrains.dokka.testApi.logger.TestLogger
import org.jetbrains.dokka.utilities.DokkaConsoleLogger
import org.jetbrains.dokka.utilities.DokkaLogger
import org.jetbrains.dokka.utilities.LoggingLevel
import kotlin.test.Test
import kotlin.test.assertFailsWith

class FailOnWarningTest : BaseAbstractTest() {

    @Test
    fun `throws exception if one or more warnings were emitted`() {
        val configuration = dokkaConfiguration {
            failOnWarning = true
            sourceSets {
                sourceSet {
                    sourceRoots = listOf("src/main/kotlin")
                }
            }
        }

        assertFailsWith<DokkaException> {
            testInline(
                """
                |/src/main/kotlin/Bar.kt
                |package sample
                |class Bar {}
                """.trimIndent(), configuration
            ) {
                pluginsSetupStage = {
                    logger.warn("Warning!")
                }
            }
        }
    }

    @Test
    fun `throws exception if one or more error were emitted`() {
        val configuration = dokkaConfiguration {
            failOnWarning = true
            sourceSets {
                sourceSet {
                    sourceRoots = listOf("src/main/kotlin")
                }
            }
        }

        assertFailsWith<DokkaException> {
            testInline(
                """
                |/src/main/kotlin/Bar.kt
                |package sample
                |class Bar {}
                """.trimIndent(), configuration
            ) {
                pluginsSetupStage = {
                    logger.error("Error!")
                }
            }
        }
    }

    @Test
    fun `does not throw if now warning or error was emitted`() {

        val configuration = dokkaConfiguration {
            failOnWarning = true
            sourceSets {
                sourceSet {
                    sourceRoots = listOf("src/main/kotlin")
                }
            }
        }


        testInline(
            """
                |/src/main/kotlin/Bar.kt
                |package sample
                |class Bar {}
                """.trimIndent(),
            configuration,
            loggerForTest = TestLogger(ZeroErrorOrWarningCountDokkaLogger())
        ) {
            /* We expect no Exception */
        }
    }

    @Test
    fun `does not throw if disabled`() {
        val configuration = dokkaConfiguration {
            failOnWarning = false
            sourceSets {
                sourceSet {
                    sourceRoots = listOf("src/main/kotlin")
                }
            }
        }


        testInline(
            """
                |/src/main/kotlin/Bar.kt
                |package sample
                |class Bar {}
                """.trimIndent(), configuration
        ) {
            pluginsSetupStage = {
                logger.warn("Error!")
                logger.error("Error!")
            }
        }
    }
}

private class ZeroErrorOrWarningCountDokkaLogger(
    logger: DokkaLogger = DokkaConsoleLogger(LoggingLevel.DEBUG)
) : DokkaLogger by logger {
    override var warningsCount: Int = 0
    override var errorsCount: Int = 0
}
