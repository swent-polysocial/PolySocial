// Contributors: Claude (Firestore emulator test setup, #117).
package com.polysocial.emulator

/**
 * Marks a test class that needs the Firebase Local Emulator Suite. `connectedCheck` skips these
 * classes unless Gradle gets `-Pemulator` (see app/build.gradle.kts), so CI, which doesn't start
 * the emulators, stays green. The README documents the command that runs them.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class EmulatorTest
