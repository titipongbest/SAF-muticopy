package com.titipong.safmulticopy

/**
 * Test needs "All files access". Android kills the app when that permission changes, so it can't
 * be switched inside a test run: the CI script runs these tests in a second pass, after granting it.
 * All other tests run first, without it.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class NeedsAllFilesAccess
